package com.wordonline.server.qa;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.wordonline.server.auth.domain.PrincipalDetails;
import com.wordonline.server.game.domain.AttackInfo;
import com.wordonline.server.game.domain.PlayerData;
import com.wordonline.server.game.domain.SessionObject;
import com.wordonline.server.game.domain.object.GameObject;
import com.wordonline.server.game.domain.object.Vector3;
import com.wordonline.server.game.domain.object.component.mob.Mob;
import com.wordonline.server.game.domain.object.prefab.PrefabType;
import com.wordonline.server.game.dto.Master;
import com.wordonline.server.game.service.ManaCharger;
import com.wordonline.server.server.entity.ServerState;
import com.wordonline.server.server.service.ServerStatusService;
import com.wordonline.server.server.service.ServerUrlProvider;
import com.wordonline.server.session.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@Profile("qa")
@ConditionalOnProperty(name = "artel.qa.enabled", havingValue = "true")
public class QaMatchController {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<PrefabType> UNITS = Set.of(
            PrefabType.FireSlime, PrefabType.WaterSlime, PrefabType.RockSlime,
            PrefabType.ElectricSlime, PrefabType.LeafSlime, PrefabType.WindSlime,
            PrefabType.RockGolem, PrefabType.AquaArcher, PrefabType.GroundTower);

    private final QaMatchProperties properties;
    private final SessionService sessions;
    private final ServerUrlProvider urls;
    private final ServerStatusService status;

    @PostMapping("/api/server/artel-qa/matches")
    @PreAuthorize("hasAuthority('WORDONLINE_SERVER')")
    public Ready create(@RequestBody Create request) {
        long caller = request.userId();
        Long peer = request.peerId();
        if (!properties.allows(caller) || (peer != null && !properties.allows(peer))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (peer != null && peer == caller) throw invalid("Choose a different QA account");
        if (status.getCurrentState() != ServerState.ACTIVE || (peer == null && properties.botId() >= 0)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        long left = peer == null ? caller : Math.min(caller, peer);
        long right = peer == null ? properties.botId() : Math.max(caller, peer);
        SessionObject session = sessions.createQaSession(left, right);
        if (!session.getGameLoop().awaitStart(COMMAND_TIMEOUT)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QA match is starting");
        }
        String url = urls.getServerUrl();
        return new Ready(session.getSessionId(), url, url + "/ws", session.getMap().name(), left, right);
    }

    @PostMapping("/api/artel-qa/matches/{id}/commands")
    public Reply executeCommand(@PathVariable String id,
            @AuthenticationPrincipal PrincipalDetails principal, @RequestBody Command request) {
        try {
            return command(id, principal, request).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ResponseStatusException response) throw response;
            throw e;
        }
    }

    CompletableFuture<Reply> command(String id, PrincipalDetails principal, Command request) {
        if (principal == null || !properties.allows(principal.memberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        long userId = principal.memberId;
        SessionObject session = sessions.getSessionObject(id);
        if (session == null || !session.isQaMatch() || session.getUserSide(userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var context = session.getGameContext();
        var result = new CompletableFuture<Reply>();
        if (!session.getGameLoop().is_running() || !context.submitAction("artelQaCommand", () -> {
            if (result.isDone()) return;
            try {
                if (sessions.getSessionObject(id) != session || context.getResultChecker().checkResult()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "QA match has ended");
                }
                result.complete(new Reply(execute(session, userId, request)));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        })) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QA match is unavailable");
        }
        CompletableFuture.delayedExecutor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).execute(() ->
                result.completeExceptionally(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "QA command timed out")));
        return result;
    }

    private String execute(SessionObject session, long userId, Command command) {
        var context = session.getGameContext();
        if (command.operation() == null) throw invalid("Operation is required");
        return switch (command.operation()) {
            case "set_mana" -> {
                Master side = side(session, userId, command.side());
                int mana = bounded(command.amount(), 0, ManaCharger.MAX_MANA);
                context.getGameSessionData().getPlayerData(side).mana = mana;
                yield "mana=" + mana;
            }
            case "set_hand" -> {
                Master side = side(session, userId, command.side());
                List<Long> ids = command.magicIds();
                long owner = session.getUserId(side);
                var available = owner > 0 ? session.getOwnedMagicIds(owner)
                        : Set.copyOf(session.getRightDeckCardIds());
                if (ids == null || ids.size() > PlayerData.MAX_CARD_NUM
                        || ids.stream().anyMatch(id -> id == null || !available.contains(id))) {
                    throw invalid("Hand must contain up to six magics available to that side");
                }
                PlayerData player = context.getGameSessionData().getPlayerData(side);
                player.cards.clear();
                player.cards.addAll(ids);
                yield "hand=" + ids;
            }
            case "damage_target", "set_health" -> {
                Mob mob = target(session, userId, command);
                if (command.operation().equals("damage_target")) {
                    mob.onDamaged(new AttackInfo(bounded(command.amount(), 1, 100_000), Set.of()));
                } else {
                    mob.setQaHealth(bounded(command.amount(), 1, mob.getMaxHp()));
                }
                yield "targetId=" + command.targetId() + " hp=" + mob.getHp();
            }
            case "spawn_unit" -> {
                Master side = side(session, userId, command.side());
                if (command.unit() == null || !UNITS.contains(command.unit()) ||
                        !coordinate(command.x(), 18) || !coordinate(command.z(), 10)) {
                    throw invalid("Invalid unit or position");
                }
                var unit = new GameObject(side, command.unit(), new Vector3(command.x(), 0, command.z()), context);
                yield "unitId=" + unit.getId();
            }
            default -> throw invalid("Unknown QA command");
        };
    }

    private static Mob target(SessionObject session, long userId, Command command) {
        int id = bounded(command.targetId(), 0, Integer.MAX_VALUE);
        Master opponent = side(session, userId, "Opponent");
        return session.getGameContext().getActiveGameObjects().stream()
                .filter(object -> object.getId() == id && !object.isDying())
                .filter(object -> !command.operation().equals("damage_target") || object.getMaster() == opponent)
                .findFirst().flatMap(object -> object.getComponentOptional(Mob.class))
                .orElseThrow(() -> invalid("Target is not a live damageable object"));
    }

    private static Master side(SessionObject session, long userId, String selector) {
        Master self = session.getUserSide(userId);
        if ("Self".equals(selector)) return self;
        if ("Opponent".equals(selector)) return self == Master.LeftPlayer ? Master.RightPlayer : Master.LeftPlayer;
        throw invalid("Choose Self or Opponent");
    }

    private static boolean coordinate(Float value, int maximum) {
        return value != null && Float.isFinite(value) && value >= 0 && value <= maximum;
    }

    private static int bounded(Integer value, int min, int max) {
        if (value == null || value < min || value > max) throw invalid("Value out of range");
        return value;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> reject(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).build();
    }

    public record Create(long userId, Long peerId) {}
    public record Ready(String sessionId, String server, String webSocketUrl, String mapType,
                        long leftId, long rightId) {}
    public record Reply(String message) {}
    public record Command(String operation, String side, Integer targetId, Integer amount,
                          List<Long> magicIds, PrefabType unit, Float x, Float z) {}
}
