package com.wordonline.server.game.domain;

import com.wordonline.server.bot.domain.BotPersona;
import com.wordonline.server.game.domain.map.GameMap;
import com.wordonline.server.game.dto.Master;
import com.wordonline.server.game.dto.PingChecker;
import com.wordonline.server.game.service.CardDeck;
import com.wordonline.server.game.service.GameContext;
import com.wordonline.server.game.service.GameLoop;
import com.wordonline.server.game.service.WordOnlineLoop;
import com.wordonline.server.game.util.DeckSeedDeriver;
import com.wordonline.server.websocket.SpectatorSubscriptionRegistry;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

@Getter
@Slf4j
// this class is used to store the session information
// it sends the frame information to the client
public class SessionObject {
    private final String sessionId;
    private long leftUserId;
    private long rightUserId;
    private final SimpMessagingTemplate template;
    private final String url;
    // STOMP destinations are fixed strings, so they are built once instead of formatted on every
    // send. The broadcast one never changes; the per-user ones are rebuilt when a user id changes.
    private final String broadcastDestination;
    private String leftUserDestination;
    private String rightUserDestination;
    private final CardDeck leftUserCardDeck;
    private final CardDeck rightUserCardDeck;
    private List<Long> leftDeckCardIds;
    private List<Long> rightDeckCardIds;
    private final PingChecker pingChecker;
    private final SessionType sessionType;
    @Setter
    private boolean qaMatch;
    private final Long scenarioId;
    // Decided before the session is created and read twice: by the creation response, and by the
    // loop when it spawns the terrain. Both must see this one value, so nothing re-rolls it.
    private final GameMap map;
    private final long randomSeed;
    // Session ids are random UUIDs, so a room list ordered by id says nothing about age. The admin
    // page needs this to tell a session created seconds ago from one that has been running for a while.
    private final Instant createdAt = Instant.now();

    // Emote input runs on a STOMP inbound thread, not on the loop thread, so several requests for
    // the same side can race here. The deadline is read and swapped with compareAndSet instead of a
    // lock; a losing thread's emote is simply dropped, which is the cooldown's own contract.
    private static final long EMOTE_COOLDOWN_MILLIS = 3_000;
    private final AtomicLong leftEmoteCooldownUntilMillis = new AtomicLong(0);
    private final AtomicLong rightEmoteCooldownUntilMillis = new AtomicLong(0);

    // Magic ids each human user owns, read once on the request thread when the session is created
    // (or when a debug user joins) so the cast on the loop thread is a set lookup, not a query.
    // A magic bought mid-match is not seen until the next session.
    private final Map<Long, Set<Long>> ownedMagicIdsByUser = new ConcurrentHashMap<>();

    public void setOwnedMagicIds(long userId, Set<Long> magicIds) {
        ownedMagicIdsByUser.put(userId, Set.copyOf(magicIds));
    }

    /** Empty for a user whose owned magics were never loaded, so such a user owns nothing. */
    public Set<Long> getOwnedMagicIds(long userId) {
        return ownedMagicIdsByUser.getOrDefault(userId, Set.of());
    }

    public Master getUserSide(long userId) {
        if (userId == leftUserId) {
            return Master.LeftPlayer;
        } else if (userId == rightUserId) {
            return Master.RightPlayer;
        } else {
            return null;
        }
    }

    /**
     * The user id playing that side, or {@code null} when the side is unknown. The reverse of
     * {@link #getUserSide(long)}: the bot input path carries only the side, so anything that has
     * to name the caster - match statistics, for one - resolves the id back through here.
     */
    public Long getUserId(Master master) {
        if (master == Master.LeftPlayer) {
            return leftUserId;
        } else if (master == Master.RightPlayer) {
            return rightUserId;
        } else {
            return null;
        }
    }

    public boolean isLeftBot() {
        return leftUserId < 0;
    }

    public boolean isRightBot() {
        return rightUserId < 0;
    }

    @Setter
    private GameLoop gameLoop;

    // Resolved by the loop's init on the request thread and read on the loop thread afterwards, so
    // a bot takeover or the match end never queries the database. Per session on purpose: the admin
    // server edits bot_personas directly, so a shared cache would go stale.
    private final Map<Long, BotPersona> botPersonas = new ConcurrentHashMap<>();
    private final Map<Long, Double> noviceProgresses = new ConcurrentHashMap<>();

    public void putBotPersona(long participantId, BotPersona persona) {
        botPersonas.put(participantId, persona);
    }

    /** The persona loaded for that participant at session creation; humans and unknown ids get DEFAULT. */
    public BotPersona getBotPersona(long participantId) {
        return botPersonas.getOrDefault(participantId, BotPersona.DEFAULT);
    }

    public void putNoviceProgress(long participantId, double progress) {
        noviceProgresses.put(participantId, progress);
    }

    /** The tutorial progress loaded for that participant at session creation; 1.0 when none was loaded. */
    public double getNoviceProgress(long participantId) {
        return noviceProgresses.getOrDefault(participantId, 1.0);
    }

    // Wired by GameLoop.initializeLoop. Written on the session creation thread and read by the
    // loop thread every frame, so it is volatile; a session with no registry has no spectators.
    @Setter
    private volatile SpectatorSubscriptionRegistry spectatorSubscriptionRegistry;

    public GameContext getGameContext() {
        return gameLoop.getGameContext();
    }

    public SessionObject(String sessionId,
                         long leftUserId,
                         long rightUserId,
                         SimpMessagingTemplate template,
                         List<Long> leftUserCards,
                         List<Long> rightUserCards,
                         SessionType sessionType,
                         Long scenarioId,
                         GameMap map) {
        this.sessionId = sessionId;
        this.leftUserId = leftUserId;
        this.rightUserId = rightUserId;
        this.template = template;
        this.url = String.format("/game/%s/frameInfos", sessionId);
        this.broadcastDestination = url + "/0";
        this.leftUserDestination = userDestination(leftUserId);
        this.rightUserDestination = userDestination(rightUserId);
        this.leftDeckCardIds = List.copyOf(leftUserCards);
        this.rightDeckCardIds = List.copyOf(rightUserCards);
        this.randomSeed = ThreadLocalRandom.current().nextLong();
        long leftDeckSeed = DeckSeedDeriver.forLeftDeck(randomSeed);
        long rightDeckSeed = DeckSeedDeriver.forRightDeck(randomSeed);
        this.leftUserCardDeck = new CardDeck(leftUserCards, leftDeckSeed);
        this.rightUserCardDeck = new CardDeck(rightUserCards, rightDeckSeed);
        log.trace("[Session] randomSeed={}, leftDeckSeed={}, rightDeckSeed={}, sessionId={}",
                randomSeed, leftDeckSeed, rightDeckSeed, sessionId);
        // Both callbacks fire on the PingChecker scheduler thread. Swapping a bot in or out changes
        // what the next frame ticks, so the work is queued for the loop thread instead of applied here.
        this.pingChecker = new PingChecker(leftUserId, rightUserId,
                userId -> submitBotToggle(userId, "activateBot", WordOnlineLoop::activateBotForUser),
                userId -> submitBotToggle(userId, "deactivateBot", WordOnlineLoop::deactivateBotForUser)
        );
        this.sessionType = sessionType;
        this.scenarioId = scenarioId;
        this.map = Objects.requireNonNull(map, "map");
    }

    public SessionObject(String sessionId,
                         long leftUserId,
                         long rightUserId,
                         SimpMessagingTemplate template,
                         List<Long> leftUserCards,
                         List<Long> rightUserCards,
                         SessionType sessionType) {
        this(sessionId, leftUserId, rightUserId, template, leftUserCards, rightUserCards, sessionType, null,
                GameMap.GRASSLAND);
    }

    public SessionObject(String sessionId,
                         long leftUserId,
                         long rightUserId,
                         SimpMessagingTemplate template,
                         List<Long> leftUserCards,
                         List<Long> rightUserCards) {
        this(sessionId, leftUserId, rightUserId, template, leftUserCards, rightUserCards, SessionType.PVP, null,
                GameMap.GRASSLAND);
    }

    private void submitBotToggle(long userId, String actionName, BiConsumer<WordOnlineLoop, Long> toggle) {
        if (getUserSide(userId) == null) {
            return;
        }
        if (gameLoop instanceof WordOnlineLoop wordOnlineLoop) {
            wordOnlineLoop.getGameContext()
                    .submitAction(actionName, () -> toggle.accept(wordOnlineLoop, userId));
        }
    }

    // this method is used to send the frame information to the client
    public void sendFrameInfo(long userId, Object data) {
        // Skip sending frame info to bots (negative user IDs)
        if (userId < 0) {
            return;
        }
        template.convertAndSend(destinationFor(userId), data);
    }

    // this method is used to broadcast frame information to spectators (userId = 0)
    public void broadcastFrameInfo(Object data) {
        if (!hasSpectators()) {
            return;
        }
        template.convertAndSend(broadcastDestination, data);
    }

    // convertAndSend serializes the payload before it reaches the broker, and the broker channel
    // dispatches inline on the game loop thread, so a broadcast with no subscriber costs a full
    // JSON encode per frame and is then dropped. Callers check this before building the payload.
    public boolean hasSpectators() {
        SpectatorSubscriptionRegistry registry = spectatorSubscriptionRegistry;
        return registry != null && registry.hasSubscribers(broadcastDestination);
    }

    /** Sends bot telemetry through the same destinations used for frame information. */
    public void sendBotThought(Object data) {
        sendToBothPlayersAndSpectators(data);
    }

    /** Sends an emote through the same destinations used for frame information. */
    public void sendEmote(Object data) {
        sendToBothPlayersAndSpectators(data);
    }

    private void sendToBothPlayersAndSpectators(Object data) {
        sendFrameInfo(leftUserId, data);
        if (rightUserId != leftUserId) {
            sendFrameInfo(rightUserId, data);
        }
        broadcastFrameInfo(data);
    }

    /**
     * Consumes the emote cooldown for {@code side} if it is currently free.
     *
     * @return {@code true} when the caller may send the emote, {@code false} when it lands inside
     *         the previous emote's cooldown window and must be dropped silently.
     */
    public boolean tryConsumeEmoteCooldown(Master side) {
        AtomicLong cooldownUntilMillis = emoteCooldownFor(side);
        if (cooldownUntilMillis == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long previousDeadline = cooldownUntilMillis.get();
        if (now < previousDeadline) {
            return false;
        }

        return cooldownUntilMillis.compareAndSet(previousDeadline, now + EMOTE_COOLDOWN_MILLIS);
    }

    private AtomicLong emoteCooldownFor(Master side) {
        if (side == Master.LeftPlayer) {
            return leftEmoteCooldownUntilMillis;
        } else if (side == Master.RightPlayer) {
            return rightEmoteCooldownUntilMillis;
        } else {
            return null;
        }
    }

    private String destinationFor(long userId) {
        if (userId == leftUserId) {
            return leftUserDestination;
        }
        if (userId == rightUserId) {
            return rightUserDestination;
        }
        return userDestination(userId);
    }

    private String userDestination(long userId) {
        return String.format("%s/%d", url, userId);
    }

    // Called from the debug HTTP endpoint, off the loop thread. The hand and the deck are plain
    // loop-thread collections now, so resetting them is queued like any other input. The user id
    // assignment stays inline because callers read it back straight after the call.
    public void setLeftUser(long userId, List<Long> cards) {
        leftUserId = userId;
        leftDeckCardIds = List.copyOf(cards);
        leftUserDestination = userDestination(userId);
        GameContext gameContext = getGameContext();
        gameContext.submitAction("setLeftUserDeck", () -> {
            gameContext.getGameSessionData().leftPlayerData.cards.clear();
            gameContext.getGameSessionData().leftCardDeck.setCards(cards);
        });
    }

    public void setRightUser(long userId, List<Long> cards) {
        rightUserId = userId;
        rightDeckCardIds = List.copyOf(cards);
        rightUserDestination = userDestination(userId);
        GameContext gameContext = getGameContext();
        gameContext.submitAction("setRightUserDeck", () -> {
            gameContext.getGameSessionData().rightPlayerData.cards.clear();
            gameContext.getGameSessionData().rightCardDeck.setCards(cards);
        });
    }

    @Override
    public String toString() {
        float deltaTime = gameLoop.getGameContext().getDeltaTime();
        double fps = deltaTime > 0 ? 1 / deltaTime : 0.0;
        return String.format("Session(users: [%d, %d], isRunning: %s, currentFps: %.2f)",
                leftUserId,
                rightUserId,
                gameLoop.is_running(),
                fps);
    }
}
