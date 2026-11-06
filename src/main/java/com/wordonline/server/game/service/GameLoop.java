package com.wordonline.server.game.service;

import com.wordonline.server.game.config.GameConfig;
import com.wordonline.server.game.config.SessionTeardownExecutor;
import com.wordonline.server.game.domain.*;
import com.wordonline.server.game.domain.map.GameMap;
import com.wordonline.server.game.domain.map.Terrain;
import com.wordonline.server.game.domain.object.GameObject;
import com.wordonline.server.game.domain.object.Vector3;
import com.wordonline.server.game.domain.object.prefab.PrefabType;
import com.wordonline.server.game.dto.*;
import com.wordonline.server.game.dto.frame.SnapshotObjectDto;
import com.wordonline.server.game.dto.frame.SnapshotResponseDto;
import com.wordonline.server.game.dto.result.ResultMmrDto;
import com.wordonline.server.game.dto.result.ResultType;
import com.wordonline.server.game.util.*;
import com.wordonline.server.websocket.SpectatorSubscriptionRegistry;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

// GameLoop is the main class that runs the game loop
@Slf4j
@RequiredArgsConstructor
public abstract class GameLoop implements Runnable {

    private enum LoopState { CREATED, RUNNING, TERMINATED }

    // The admin room list, the lobby readiness check and the bot scheduler all read is_running(),
    // so it has to mean "the loop thread is ticking" rather than "a GameLoop object exists".
    // RUNNING is set from inside the loop thread and cleared in a finally block, so neither the
    // window before Thread.start() takes effect nor a thread dying on an Error can leave a session
    // advertising itself as alive.
    private volatile LoopState state = LoopState.CREATED;
    private volatile boolean stopRequested = false;
    private final CountDownLatch startSignal = new CountDownLatch(1);

    // The thread ticking this loop, captured on entry so the watchdog can interrupt a
    // stalled loop and snapshot where it is stuck. close() alone cannot end a loop whose
    // thread never returns to the while condition.
    private volatile Thread loopThread;

    // Written by the loop thread once per completed frame and read by monitoring threads.
    // deltaTime cannot serve that purpose: it holds the last completed frame's duration, so a
    // loop thread that dies mid-frame keeps reporting a plausible fps forever. The age of this
    // timestamp is the one signal that keeps moving when the loop does not.
    @Getter
    private volatile long lastFrameEndMillis = System.currentTimeMillis();

    // Frame counts stored in the database before the tick rate became a session setting
    // (bot_personas.reaction_interval_frames, the FrameNumGte trigger value) count 20-FPS frames.
    // They are read as that many twentieths of a second, whatever the session's own rate.
    public static final int LEGACY_TICK_RATE = 20;
    public static final float LEGACY_FRAME_SECONDS = 1f / LEGACY_TICK_RATE;

    // SyncFrameDataSystem replaces the frame message with a full snapshot every half second of game
    // time, and on the very first frame so the client learns the tick rate before anything else.
    // The loop builds the snapshot for it, so both sides read the period from here rather than
    // from two literals that can drift apart.
    public static final float SYNC_INTERVAL_SECONDS = 0.5f;

    public static int syncIntervalFrames(int tickRate) {
        return Math.max(1, Math.round(SYNC_INTERVAL_SECONDS * tickRate));
    }

    public static boolean isSyncFrame(int frameNum, int tickRate) {
        return frameNum == 1 || frameNum % syncIntervalFrames(tickRate) == 0;
    }

    public SessionObject sessionObject;

    // Spectator subscriptions are counted for the whole application, so the registry is a
    // singleton the loop hands to its session. Setter injection rather than a constructor
    // argument: GameLoop's constructor is chained through WordOnlineLoop into PveLoop, and this
    // dependency belongs to none of them.
    private SpectatorSubscriptionRegistry spectatorSubscriptionRegistry;
    private Runnable onTerminated;

    // The database writes that follow the result broadcast run here, off the loop thread. Setter
    // injection for the same reason as the spectator registry; a loop built by hand in a test runs
    // them inline.
    private Executor teardownExecutor = Runnable::run;

    // Both sides' ratings as the session started, read on the thread that creates the session.
    // A player is in one session at a time, so they are still the stored ratings when the match
    // ends, and the result needs no query on the loop thread.
    private MmrService.RatingRef leftRatingAtStart;
    private MmrService.RatingRef rightRatingAtStart;

    // Completes when the writes handleGameEnd started have finished. SessionService runs the rest
    // of the teardown after it, so the writes keep the order they had when all of them ran inline.
    private volatile CompletableFuture<Void> endPersistence = CompletableFuture.completedFuture(null);

    private final MmrService mmrService;
    private final UserService userService;

    protected MmrService getMmrService() {
        return mmrService;
    }

    protected UserService getUserService() {
        return userService;
    }

    @Getter
    protected final GameContext gameContext;

    public final Parameters parameters;

    @Getter
    protected volatile List<SnapshotObjectDto> lastSnapshotObjects = List.of();
    protected volatile int lastSnapshotFrameNum = 0;

    public void init(SessionObject sessionObject, Runnable onTerminated) {
        initializeLoop(sessionObject, onTerminated, true);
        // A playground session never reaches a rating and must not read one.
        if (sessionObject.getSessionType() != SessionType.Playground) {
            leftRatingAtStart = mmrService.resolveRatingRef(sessionObject.getLeftUserId());
            rightRatingAtStart = mmrService.resolveRatingRef(sessionObject.getRightUserId());
        }
    }

    @Autowired
    public void setSpectatorSubscriptionRegistry(SpectatorSubscriptionRegistry spectatorSubscriptionRegistry) {
        this.spectatorSubscriptionRegistry = spectatorSubscriptionRegistry;
    }

    @Autowired
    public void setTeardownExecutor(@Qualifier(SessionTeardownExecutor.BEAN_NAME) Executor teardownExecutor) {
        this.teardownExecutor = teardownExecutor;
    }

    public CompletableFuture<Void> getEndPersistence() {
        return endPersistence;
    }

    // Runs the database work that follows the result broadcast off the loop thread. Each step
    // catches its own failure so the steps after it still run.
    protected final void persistAfterGameEnd(Runnable... steps) {
        endPersistence = CompletableFuture.runAsync(() -> {
            for (Runnable step : steps) {
                try {
                    step.run();
                } catch (Exception e) {
                    log.warn("[GameEnd] Failed to persist a match end step; sessionId: {}",
                            sessionObject.getSessionId(), e);
                }
            }
        }, teardownExecutor);
    }

    protected final void initializeLoop(SessionObject sessionObject, Runnable onTerminated, boolean createRightPlayer) {
        this.sessionObject = sessionObject;
        this.onTerminated = onTerminated;
        // Every loop, PVP and PVE alike, reaches this method, so this is the one place the session
        // learns how to tell whether a spectator is listening.
        sessionObject.setSpectatorSubscriptionRegistry(spectatorSubscriptionRegistry);

        new GameObject(Master.LeftPlayer, PrefabType.Player, GameConfig.LEFT_PLAYER_POSITION, gameContext);
        if (createRightPlayer) {
            new GameObject(Master.RightPlayer, PrefabType.Player, GameConfig.RIGHT_PLAYER_POSITION, gameContext);
        }
        new GameObject(Master.None, PrefabType.Wall, Vector3.ZERO, gameContext);

        // The loop does not choose the map: the session already holds the one the creation response
        // told the client. The terrain is set before the cells are spawned so that nothing asks
        // for it while it is still empty.
        GameMap map = sessionObject.getMap();
        gameContext.setTerrain(map.terrain());
        spawnTerrain(map.terrain());
        log.info("[Map] session={} type={} map={}", sessionObject.getSessionId(), sessionObject.getSessionType(), map);
    }

    // The client draws what it is told, so the map reaches it as one object per cell.
    private void spawnTerrain(Terrain terrain) {
        for (Terrain.Cell cell : terrain.waterCells()) {
            new GameObject(Master.None, PrefabType.RiverWater, cell.center(), gameContext);
        }
        for (Terrain.Cell cell : terrain.bridgeCells()) {
            new GameObject(Master.None, PrefabType.RiverBridge, cell.center(), gameContext);
        }
    }

    public boolean is_running() {
        return state == LoopState.RUNNING;
    }

    // Lets the creator wait for the first tick instead of reporting a session ready the moment the
    // object exists. Returns false on timeout; the caller decides whether that is fatal.
    public boolean awaitStart(Duration timeout) {
        try {
            return startSignal.await(timeout.toMillis(), TimeUnit.MILLISECONDS) && is_running();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void close() {
        stopRequested = true;
    }

    // Best effort: wakes a thread parked in sleep/wait, but cannot break a synchronized
    // wait or a runaway loop - callers must not assume the thread actually dies.
    public void interruptLoopThread() {
        Thread thread = loopThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    // Where the loop thread is right now; the watchdog records this as the primary
    // evidence of what a stalled loop was blocked on. Empty when the thread is gone.
    public StackTraceElement[] captureLoopThreadStackTrace() {
        Thread thread = loopThread;
        if (thread == null || !thread.isAlive()) {
            return new StackTraceElement[0];
        }
        return thread.getStackTrace();
    }

    @Override
    public void run() {
        runLoop();
    }

    // this method is called when the game loop is started
    private void runLoop() {
        // Nanoseconds, not 1000 / tickRate milliseconds: integer milliseconds turn 60 into 16 ms,
        // which is 62.5 frames a second.
        long frameNanos = TimeUnit.SECONDS.toNanos(1) / gameContext.getTickRate();

        loopThread = Thread.currentThread();
        state = LoopState.RUNNING;
        startSignal.countDown();

        try {
            long frameStart = System.nanoTime();
            long nextFrameStart = frameStart + frameNanos;
            while (!stopRequested) {
                try {
                    // Every write to game state happens on this thread. update() runs
                    // GameActionSystem first, applying the input, bot and ping actions other
                    // threads queued since the last frame, and nothing else mutates game state,
                    // so no lock is taken here or anywhere below.
                    update();
                } catch (Exception e) {
                    log.error("[ERROR] {}", e.getMessage(), e);
                    finalizeAfterFailure();
                    break;
                }

                // Sleeping until a deadline rather than for frameNanos minus the work keeps the
                // average rate exact: the few tens of microseconds a sleep oversleeps come off the
                // next frame's sleep instead of adding up.
                long sleepNanos = nextFrameStart - System.nanoTime();
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(Duration.ofNanos(sleepNanos));
                    } catch (InterruptedException ignored) {
                    }
                }
                long now = System.nanoTime();
                // deltaTime stays the measured length of this frame rather than a fixed step, and
                // is never zero so a timer that divides by it or waits on it always moves.
                gameContext.setDeltaTime(Math.max(now - frameStart, 1L) / 1_000_000_000f);
                frameStart = now;
                // A loop that fell more than a frame behind starts a new schedule from now instead
                // of running frames back to back to catch up; deltaTime already carries the delay.
                nextFrameStart = Math.max(nextFrameStart + frameNanos, now);
                lastFrameEndMillis = System.currentTimeMillis();
            }
        } finally {
            // An Error thrown out of update() escapes the catch above. Without this the thread would
            // die while the session stayed in the registry, permanently reported as active.
            state = LoopState.TERMINATED;
            startSignal.countDown();

            if (onTerminated != null) {
                try {
                    onTerminated.run();
                } catch (Exception e) {
                    log.warn("onTerminated failed", e);
                }
            }
        }
    }

    // A crashed frame must still deliver a result and release both users from the in-game state.
    // handleGameEnd() calls close(), so an unset stopRequested means the match was not ended yet.
    private void finalizeAfterFailure() {
        if (stopRequested) {
            return;
        }
        try {
            gameContext.getResultChecker().setEnd();
            handleGameEnd();
        } catch (Exception e) {
            log.error("failed to finalize match after loop failure", e);
        }
    }

    // this method is called when the game loop is stopped
    abstract void update();

    protected void handleGameEnd() {
        Master loser = gameContext.getResultChecker().getLoser();

        long leftId = sessionObject.getLeftUserId();
        long rightId = sessionObject.getRightUserId();
        ResultType outcomeLeft;
        if (loser == Master.LeftPlayer) {
            outcomeLeft = ResultType.Lose;
        } else if (loser == Master.RightPlayer) {
            outcomeLeft = ResultType.Win;
        } else {
            outcomeLeft = ResultType.Draw;
        }

        // Read now only when init did not run, which is a loop built by hand.
        MmrService.RatingRef left = leftRatingAtStart != null ? leftRatingAtStart : mmrService.resolveRatingRef(leftId);
        MmrService.RatingRef right = rightRatingAtStart != null ? rightRatingAtStart : mmrService.resolveRatingRef(rightId);

        boolean rated = !sessionObject.isQaMatch() && (sessionObject.getSessionType() == SessionType.PVP
                || sessionObject.getSessionType() == SessionType.Practice);
        ResultMmrDto mmrDto = rated
                ? MmrService.computeMatchResult(left, right, outcomeLeft,
                        sessionObject.getSessionType() == SessionType.PVP)
                : new ResultMmrDto(left.rating(), right.rating(), left.rating(), right.rating());
        // The result goes out before anything is written: the player sees the numbers that are
        // about to be stored, not the ones that have been.
        gameContext.getResultChecker().broadcastResult(mmrDto);

        if (sessionObject.isQaMatch()) {
            close();
            return;
        }

        persistAfterGameEnd(
                () -> {
                    if (rated) {
                        mmrService.saveMatchResult(left, right, mmrDto);
                    }
                },
                () -> userService.markOnline(leftId),
                () -> userService.markOnline(rightId));

        // 3) Loop end
        close();
    }

    // 2) Build snapshot. Only called on sync frames: nothing else reads the result.
    protected void buildSnapshot() {
        lastSnapshotObjects = gameContext.getGameObjects()
                .stream()
                .map(SnapshotMapper::toDto)
                .toList();
        lastSnapshotFrameNum = gameContext.getFrameNum();
    }

    public SnapshotResponseDto getLastSnapshot(Long userId) {
        List<Long> cards;
        if (gameContext.getSessionObject().getLeftUserId() == userId) {
            cards = gameContext.getGameSessionData().leftPlayerData.cards;
        } else if (gameContext.getSessionObject().getRightUserId() == userId) {
            cards = gameContext.getGameSessionData().rightPlayerData.cards;
        } else {
            cards = List.of();
        }
        return new SnapshotResponseDto(lastSnapshotFrameNum, lastSnapshotObjects, cards);
    }
}
