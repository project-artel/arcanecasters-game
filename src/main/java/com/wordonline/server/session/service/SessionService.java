package com.wordonline.server.session.service;

import com.wordonline.server.game.domain.SessionObject;
import com.wordonline.server.bot.domain.BotParticipant;
import com.wordonline.server.bot.service.BotPersonaService;
import com.wordonline.server.game.config.SessionTeardownExecutor;
import com.wordonline.server.game.domain.SessionType;
import com.wordonline.server.game.dto.Master;
import com.wordonline.server.game.service.GameContext;
import com.wordonline.server.game.service.GameLoop;
import com.wordonline.server.game.service.ResultChecker;
import com.wordonline.server.game.service.UserService;
import com.wordonline.server.game.service.UserScenarioService;
import com.wordonline.server.lobby.client.LobbySessionClient;
import com.wordonline.server.session.dto.RoomInfoDto;
import com.wordonline.server.session.dto.SessionDto;
import com.wordonline.server.session.util.GameLoopFactory;
import com.wordonline.server.session.util.SessionObjectFactory;
import com.wordonline.server.statistic.domain.GameSessionStatus;
import com.wordonline.server.statistic.service.GameSessionRecordService;
import com.wordonline.server.statistic.service.StatisticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final Duration SESSION_START_TIMEOUT = Duration.ofSeconds(2);
    private static final Map<String, SessionObject> sessions = new ConcurrentHashMap<>();
    private static final Map<String, String> sessionIdsByAttemptId = new ConcurrentHashMap<>();

    private final SubmissionPublisher<Integer> onSessionNumChange = new SubmissionPublisher<>();

    private final SessionObjectFactory sessionObjectFactory;
    private final GameLoopFactory gameLoopFactory;
    private final StatisticService statisticService;
    private final GameSessionRecordService gameSessionRecordService;
    private final UserService userService;
    private final BotPersonaService botPersonaService;
    private final UserScenarioService userScenarioService;
    private final LobbySessionClient lobbySessionClient;
    private final Executor teardownExecutor;

    public SessionService(SessionObjectFactory sessionObjectFactory,
                          GameLoopFactory gameLoopFactory,
                          StatisticService statisticService,
                          GameSessionRecordService gameSessionRecordService,
                          UserService userService,
                          BotPersonaService botPersonaService,
                          UserScenarioService userScenarioService,
                          LobbySessionClient lobbySessionClient,
                          @Qualifier(SessionTeardownExecutor.BEAN_NAME) Executor teardownExecutor) {
        this.sessionObjectFactory = sessionObjectFactory;
        this.gameLoopFactory = gameLoopFactory;
        this.statisticService = statisticService;
        this.gameSessionRecordService = gameSessionRecordService;
        this.userService = userService;
        this.botPersonaService = botPersonaService;
        this.userScenarioService = userScenarioService;
        this.lobbySessionClient = lobbySessionClient;
        this.teardownExecutor = teardownExecutor;
    }

    public void subscribeSessionNumChange(Flow.Subscriber<Integer> subscriber) {
        onSessionNumChange.subscribe(subscriber);
    }

    public SessionObject createSession(SessionDto sessionDto) {
        rejectReservedQaId(sessionDto);
        return createSessionObject(sessionDto, false);
    }

    public synchronized SessionObject createQaSession(long left, long right) {
        for (SessionObject session : sessions.values()) {
            if (!session.isQaMatch()) continue;
            if (session.getLeftUserId() == left && session.getRightUserId() == right) return session;
            if (session.getUserSide(left) != null
                    || (right > 0 && session.getUserSide(right) != null)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "QA account already has a match");
            }
        }
        var type = right < 0 ? SessionType.Practice : SessionType.PVP;
        return createSessionObject(new SessionDto("artel-qa-" + java.util.UUID.randomUUID(),
                left, right, type, null), true);
    }

    public synchronized SessionCreationResult createSession(String attemptId, SessionDto sessionDto) {
        rejectReservedQaId(sessionDto);
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("attemptId must not be blank");
        }

        String existingSessionId = sessionIdsByAttemptId.get(attemptId);
        if (existingSessionId != null) {
            if (!existingSessionId.equals(sessionDto.sessionId())) {
                throw new IllegalArgumentException("attemptId is already associated with another session");
            }
            SessionObject existing = sessions.get(existingSessionId);
            return new SessionCreationResult(attemptId, existingSessionId, isSessionActive(existingSessionId),
                    existing == null ? null : existing.getMap());
        }

        SessionObject existingSession = sessions.get(sessionDto.sessionId());
        if (existingSession != null) {
            throw new IllegalArgumentException("sessionId already exists");
        }

        SessionObject created = createSessionObject(sessionDto, false);
        sessionIdsByAttemptId.put(attemptId, sessionDto.sessionId());
        return new SessionCreationResult(attemptId, sessionDto.sessionId(),
                awaitSessionReady(sessionDto.sessionId()), created.getMap());
    }

    private static void rejectReservedQaId(SessionDto sessionDto) {
        if (sessionDto.sessionId() != null && sessionDto.sessionId().startsWith("artel-qa-")) {
            throw new IllegalArgumentException("Reserved QA session ID");
        }
    }

    // isSessionActive() is only true once the loop thread is ticking, so the lobby would otherwise
    // race the thread start and be told a brand new session is not ready. Wait for the first tick
    // instead; a timeout leaves the session in place and reports not-ready so the lobby can retry
    // with the same attemptId.
    private boolean awaitSessionReady(String sessionId) {
        SessionObject sessionObject = sessions.get(sessionId);
        if (sessionObject == null || sessionObject.getGameLoop() == null) {
            return false;
        }

        boolean started = sessionObject.getGameLoop().awaitStart(SESSION_START_TIMEOUT);
        if (!started) {
            log.warn("[Session] Loop did not start within {}; sessionId: {}", SESSION_START_TIMEOUT, sessionId);
        }
        return started;
    }

    private SessionObject createSessionObject(SessionDto sessionDto, boolean qaMatch) {
        SessionObject sessionObject = sessionObjectFactory.createSessionObject(sessionDto);
        sessionObject.setQaMatch(qaMatch);
        GameLoop loop = gameLoopFactory.create(sessionObject.getSessionType());
        if (sessionDto.tickRate() != null) {
            loop.getGameContext().setTickRate(sessionDto.tickRate());
        }

        sessionObject.setGameLoop(loop);
        loop.init(sessionObject, () -> onLoopTerminated(sessionObject));

        if (!qaMatch && sessionObject.getSessionType() != SessionType.Playground && !sessionObject.getSessionId().contains("debug")) {
            statisticService.createBuilder(loop.getGameContext());
            gameSessionRecordService.recordStart(sessionObject);
        }

        sessions.put(sessionObject.getSessionId(), sessionObject);
        // A platform thread on purpose, not a virtual one. Virtual threads are not preempted and share
        // a carrier pool with the virtual Tomcat request threads, so under HTTP load a loop waits for a
        // carrier after every sleep: measured with 100 bot sessions, 56-71% of frames came in over 60ms
        // (p99 interval 212-986ms) against 0.07% on platform threads.
        Thread thread = new Thread(loop);
        try {
            thread.start();
        } catch (RuntimeException | Error exception) {
            sessions.remove(sessionObject.getSessionId(), sessionObject);
            sessionObject.getPingChecker().close();
            if (!qaMatch && sessionObject.getSessionType() != SessionType.Playground) {
                gameSessionRecordService.recordEnd(sessionObject.getSessionId(),
                        GameSessionStatus.ABANDONED, "START_FAILED", null, null);
            }
            throw exception;
        }
        submitSessionNumChange();
        log.info("[Session] Session created; sessionId: {}, map: {}", sessionObject.getSessionId(),
                sessionObject.getMap());
        return sessionObject;
    }

    public boolean isSessionActive(String sessionId) {
        SessionObject sessionObject = sessions.get(sessionId);
        return sessionObject != null
                && sessionObject.getGameLoop() != null
                && sessionObject.getGameLoop().is_running();
    }

    private void onLoopTerminated(SessionObject sessionObject) {
        // The watchdog reaps stuck sessions through the same removal; whichever caller
        // wins the remove owns the teardown, so a zombie loop thread that wakes up after
        // being reaped must not record a second result or free the users again.
        if (!sessions.remove(sessionObject.getSessionId(), sessionObject)) {
            log.info("[Session] Loop terminated after the session was already reaped; sessionId: {}",
                    sessionObject.getSessionId());
            return;
        }
        sessionObject.getPingChecker().close();
        submitSessionNumChange();

        if (sessionObject.isQaMatch() || sessionObject.getSessionType() == SessionType.Playground) {
            sessionIdsByAttemptId.values().removeIf(sessionObject.getSessionId()::equals);
            return;
        }

        // Everything above decides who owns the teardown and releases what this server holds in
        // memory, so it stays on the loop thread and ahead of the watchdog. What follows only
        // writes to the database and the lobby; it runs on the teardown executor so that a
        // session's end does not hold its loop thread for a dozen round trips. It waits for the
        // rating and user status writes handleGameEnd started, which ran first when all of this
        // was inline.
        GameLoop loop = sessionObject.getGameLoop();
        CompletableFuture<Void> endPersistence = loop == null || loop.getEndPersistence() == null
                ? CompletableFuture.completedFuture(null)
                : loop.getEndPersistence();
        endPersistence.whenCompleteAsync((ignored, error) -> persistSessionEnd(sessionObject), teardownExecutor);
    }

    private void persistSessionEnd(SessionObject sessionObject) {
        try {
            persistSessionEndSteps(sessionObject);
        } catch (Exception e) {
            log.warn("[Session] Failed to persist the session end; sessionId: {}", sessionObject.getSessionId(), e);
        }
    }

    private void persistSessionEndSteps(SessionObject sessionObject) {
        GameContext gameContext = sessionObject.getGameContext();
        ResultChecker resultChecker = gameContext.getResultChecker();
        Master loser = resultChecker.getLoser();

        Long statisticGameId = null;
        try {
            statisticGameId = statisticService
                    .saveGameResult(gameContext, loser, sessionObject.getSessionType())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[Session] Failed to save game statistics; sessionId: {}", sessionObject.getSessionId(), e);
        }

        try {
            gameSessionRecordService.recordEnd(sessionObject.getSessionId(),
                    loser == null ? GameSessionStatus.DRAW : GameSessionStatus.COMPLETED,
                    null, null, statisticGameId);
        } catch (Exception e) {
            log.warn("[Session] Failed to record the session end; sessionId: {}", sessionObject.getSessionId(), e);
        }

        notifyLobbySessionEnded(sessionObject);

        if (loser == null) {
            log.info("[Session] Session ended with no winner; sessionId: {}", sessionObject.getSessionId());
            return;
        }

        long winnerId = resultChecker.getWinnerId();

        if (!sessionObject.getSessionId().contains("debug")) {
            if (sessionObject.getSessionType() == SessionType.PVE && loser == Master.RightPlayer) {
                try {
                    userScenarioService.markFinished(sessionObject.getLeftUserId(), sessionObject.getScenarioId());
                } catch (Exception e) {
                    log.warn("[Session] Failed to mark the scenario finished; sessionId: {}",
                            sessionObject.getSessionId(), e);
                }
            }
            if (winnerId >= 0) {
                try {
                    userService.incrementTotalWins(winnerId);
                } catch (Exception e) {
                    log.warn("[Session] Failed to increment total wins; sessionId: {}",
                            sessionObject.getSessionId(), e);
                }
                advanceNoviceProgressAfterHospitalityWin(sessionObject, winnerId);
            }
        }

        log.info("[Session] Session removed; sessionId: {}", sessionObject.getSessionId());
    }

    // Called by the watchdog for a session whose loop stopped ticking. Winning the remove
    // makes this the owner of the teardown (see onLoopTerminated); everything a normal end
    // would release is released here, because the loop thread may never run its finally
    // block. The thread itself is only interrupted best-effort - a synchronized wait
    // ignores it - so the record, the lobby ticket, and the users must not depend on it.
    public boolean reapStuckSession(SessionObject sessionObject, String endDetail) {
        String sessionId = sessionObject.getSessionId();
        if (!sessions.remove(sessionId, sessionObject)) {
            return false;
        }
        sessionObject.getPingChecker().close();
        submitSessionNumChange();

        if (sessionObject.isQaMatch() || sessionObject.getSessionType() == SessionType.Playground) {
            var loop = sessionObject.getGameLoop();
            loop.close();
            loop.interruptLoopThread();
            return true;
        }

        Long statisticGameId = null;
        try {
            statisticGameId = statisticService
                    .saveAbandonedGameResult(sessionObject.getGameContext(), sessionObject.getSessionType())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[Session] Failed to save abandoned game statistics; sessionId: {}", sessionId, e);
        }

        gameSessionRecordService.recordEnd(sessionId, GameSessionStatus.ABANDONED,
                "LOOP_STUCK", endDetail, statisticGameId);

        notifyLobbySessionEnded(sessionObject);
        userService.markOnline(sessionObject.getLeftUserId());
        userService.markOnline(sessionObject.getRightUserId());

        GameLoop loop = sessionObject.getGameLoop();
        if (loop != null) {
            loop.close();
            loop.interruptLoopThread();
        }

        log.error("[Session] Reaped stuck session; sessionId: {}", sessionId);
        return true;
    }

    /**
     * Moves the winner along the tutorial when the opponent they beat was the hospitality bot.
     *
     * <p>Only on a win. The bot holds back by exactly this number, so a player who is losing is not
     * someone to give less help to - and because it eases off the further behind they are, they get
     * there eventually. A session that ends without a winner, including one the watchdog reaps,
     * leaves the player where they were: they replay the match rather than skipping it.
     */
    private void advanceNoviceProgressAfterHospitalityWin(SessionObject sessionObject, long winnerId) {
        if (sessionObject.getSessionType() != SessionType.Practice) {
            return;
        }

        try {
            long loserId = winnerId == sessionObject.getLeftUserId()
                    ? sessionObject.getRightUserId()
                    : sessionObject.getLeftUserId();
            if (isHospitalityBot(sessionObject, loserId)) {
                userService.advanceNoviceProgress(winnerId);
            }
        } catch (Exception e) {
            // The player stays where they were and meets the tutorial opponent again on the same
            // terms. That is a repeated match, not a broken account, and it must not take the
            // teardown with it.
            log.warn("[Session] Failed to advance novice progress; sessionId: {}",
                    sessionObject.getSessionId(), e);
        }
    }

    // The persona was loaded when the session was created; this runs on the loop thread, which
    // must not wait on the database.
    private boolean isHospitalityBot(SessionObject sessionObject, long participantId) {
        return BotParticipant.isBot(participantId)
                && sessionObject.getBotPersona(participantId).hospitality();
    }

    // The lobby's match ticket lives in Redis and stays MATCHED until it hears the session ended,
    // which would silently swallow the player's next queue attempt. The lobby reconciler is the
    // safety net, so a failed notification is tolerable here; a failed teardown is not.
    private void notifyLobbySessionEnded(SessionObject sessionObject) {
        String sessionId = sessionObject.getSessionId();
        if (sessionId.contains("debug")) {
            return;
        }

        try {
            lobbySessionClient.notifySessionEnded(sessionId);
        } catch (Exception e) {
            log.warn("[Session] Failed to notify the lobby of session end; sessionId: {}", sessionId, e);
        }
    }

    public void submitSessionNumChange() {
        onSessionNumChange.submit(sessions.size());
    }

    public SessionObject getSessionObject(String sessionId) {
        return sessions.get(sessionId);
    }

    // Snapshot for monitoring sweeps; a copy so callers iterate without seeing concurrent changes.
    public List<SessionObject> getSessionObjects() {
        return List.copyOf(sessions.values());
    }

    public long getActiveSessions() {
        return sessions.values().stream().filter(s -> s.getGameLoop().is_running()).count();
    }

    public String getHealthLog() {
        long activeSessions = getActiveSessions();
        String joined = String.join("\n", sessions.values().stream().map(Object::toString).toList());
        return "Sessions: num :" + activeSessions + " | " + joined;
    }

    /** User ids on either side of a session this server holds right now. */
    public Set<Long> getParticipantUserIds() {
        Set<Long> userIds = new HashSet<>();
        for (SessionObject sessionObject : sessions.values()) {
            userIds.add(sessionObject.getLeftUserId());
            userIds.add(sessionObject.getRightUserId());
        }
        return userIds;
    }

    public Optional<SessionObject> findByUserId(long userId) {
        return sessions.values().stream()
                .filter(s -> s.getLeftUserId() == userId || s.getRightUserId() == userId)
                .findFirst();
    }

    public void clearSessions() {
        sessions.clear();
        sessionIdsByAttemptId.clear();
    }

    // Ordered oldest first. Without an explicit sort the order is ConcurrentHashMap bucket order over
    // random UUID keys, so "the room near the top of the admin list" would mean nothing reproducible.
    public List<RoomInfoDto> getAllActiveSessionsInfo(String baseUrl) {
        return sessions.values().stream()
                .filter(s -> !s.isQaMatch())
                .filter(s -> s.getGameLoop() != null && s.getGameLoop().is_running())
                .sorted(Comparator.comparing(SessionObject::getCreatedAt)
                        .thenComparing(SessionObject::getSessionId))
                .map(s -> new RoomInfoDto(
                        s.getSessionId(),
                        Long.valueOf(s.getLeftUserId()),
                        Long.valueOf(s.getRightUserId()),
                        baseUrl,
                        s.getCreatedAt(),
                        s.getMap()))
                .toList();
    }
}
