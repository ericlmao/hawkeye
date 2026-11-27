package gg.moonrise.hawkeye.core.service.dev;

import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.dev.pipeline.DevDataRun;
import gg.moonrise.hawkeye.core.service.dev.pipeline.DevRunSettings;
import gg.moonrise.hawkeye.core.storage.dev.DevDataStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Generates fake history in dev mode ({@code /hawkeye dev generate}, {@code config.yml: dev-mode.enabled}), one
 * run at a time per server. Dev mode is for developing Hawkeye (experimenting with storage, trying lookups and
 * rollbacks at scale), never for a real server. Each run gets its own {@code Hawkeye-Dev} thread and a small
 * {@code Hawkeye-Dev-Pack} pool that compresses segment frames; both end with the run. Its progress is reported
 * from the {@code Hawkeye-Maintenance} thread while it runs ({@link DevDataRun}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class DevDataService {

    /** How often a running generation reports its progress to the listener. */
    public static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(10);

    /** Threads compressing segment frames during a run. */
    public static final int PACK_THREADS = 3;

    /** How long shutdown waits for a running generation to stop at a batch boundary. */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final @NotNull StorageManager storage;
    private final @NotNull DevDataStore store;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile DevDataRun current;
    private volatile CompletableFuture<DevDataOutcome> finished;
    private volatile int batchEvents;
    private volatile long progressMillis = PROGRESS_INTERVAL.toMillis();

    /**
     * Whether dev mode is on.
     *
     * @return {@code config.yml: dev-mode.enabled} as last reloaded
     */
    public boolean enabled() {
        return configProvider.get().devMode().enabled();
    }

    /**
     * Starts generating fake data, unless dev mode is off, storage is not ready or a run is going.
     *
     * @param request  what to write
     * @param listener hears how it goes
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<DevDataOutcome> start(@NotNull DevDataRequest request,
            @NotNull DevDataListener listener) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(new DevDataOutcome.Disabled());
        }
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return CompletableFuture.completedFuture(new DevDataOutcome.NotReady());
        }
        if (!busy.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new DevDataOutcome.AlreadyRunning());
        }
        ExecutorService pack = Executors.newFixedThreadPool(PACK_THREADS, HawkeyeExecutors.threads("Hawkeye-Dev-Pack"));
        ExecutorService thread = Executors.newSingleThreadExecutor(HawkeyeExecutors.threads("Hawkeye-Dev"));
        DevDataRun run = new DevDataRun(request, settings(), store, ready.get().home(), clock, pack,
                executors.maintenance(), listener, this::enabled);
        CompletableFuture<DevDataOutcome> outcome = new CompletableFuture<>();
        current = run;
        finished = outcome;
        try {
            thread.execute(() -> outcome.complete(run(run, pack, thread)));
        } catch (RejectedExecutionException e) {
            outcome.complete(release(new DevDataOutcome.Failed("the dev data thread could not start"), pack, thread));
        }
        return outcome;
    }

    /**
     * Asks the running generation to stop after the batch it is on; what it wrote stays.
     *
     * @return {@code false} when nothing is generated
     */
    public boolean cancel() {
        DevDataRun run = current;
        if (run == null) {
            return false;
        }
        run.cancel();
        return true;
    }

    /**
     * Where the running generation stands.
     *
     * @return a fresh snapshot, or empty when none runs (or its node is not registered yet)
     */
    public @NotNull Optional<DevDataProgress> progress() {
        DevDataRun run = current;
        return run == null ? Optional.empty() : run.progress();
    }

    /**
     * Whether fake data is being generated.
     *
     * @return {@code true} while a run goes
     */
    public boolean running() {
        return busy.get();
    }

    /** Cancels a running generation and waits briefly for it to stop at a batch boundary. */
    @PreDestroy
    public void shutdown() {
        CompletableFuture<DevDataOutcome> outcome = finished;
        if (!cancel() || outcome == null) {
            return;
        }
        try {
            outcome.get(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Generating fake data did not stop within {} s", SHUTDOWN_WAIT.toSeconds());
        } catch (ExecutionException e) {
            log.warn("Generating fake data failed while stopping", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Changes the batch size and progress interval of later runs, so tests need neither the 16,384 events of the
     * smallest configurable segment nor 10 seconds. Tests only.
     *
     * @param events         events per segment, or 0 for {@code archive.segment-events}
     * @param progressMillis the progress interval
     */
    void tune(int events, long progressMillis) {
        this.batchEvents = events;
        this.progressMillis = progressMillis;
    }

    private @NotNull DevDataOutcome run(@NotNull DevDataRun run, @NotNull ExecutorService pack,
            @NotNull ExecutorService thread) {
        DevDataOutcome outcome;
        try {
            outcome = run.call();
        } catch (Throwable e) {
            // Anything, an Error included: the outcome must complete and the next run must be able to start.
            log.error("Generating fake data failed", e);
            outcome = new DevDataOutcome.Failed(Objects.requireNonNullElse(e.getMessage(),
                    e.getClass().getSimpleName()));
        }
        return release(outcome, pack, thread);
    }

    /** Ends the run's threads and lets the next run start, before its outcome is published. */
    private @NotNull DevDataOutcome release(@NotNull DevDataOutcome outcome, @NotNull ExecutorService pack,
            @NotNull ExecutorService thread) {
        pack.shutdown();
        thread.shutdown();
        current = null;
        busy.set(false);
        return outcome;
    }

    private @NotNull DevRunSettings settings() {
        HawkeyeConfig config = configProvider.get();
        int events = batchEvents > 0 ? batchEvents : config.archive().segmentEvents();
        return new DevRunSettings(config.server().name(), storage.worldScope(), events, progressMillis, clock.millis(),
                config.archive().settleSeconds() * 1000L);
    }
}
