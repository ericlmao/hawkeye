package gg.moonrise.hawkeye.core.service.dev.pipeline;

import gg.moonrise.hawkeye.core.queue.id.IdAssigner;
import gg.moonrise.hawkeye.core.service.dev.DevDataListener;
import gg.moonrise.hawkeye.core.service.dev.DevDataOutcome;
import gg.moonrise.hawkeye.core.service.dev.DevDataProgress;
import gg.moonrise.hawkeye.core.service.dev.DevDataRequest;
import gg.moonrise.hawkeye.core.service.dev.fake.FakeServer;
import gg.moonrise.hawkeye.core.storage.dev.DevDataStore;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One run of fake data (docs/architecture.md, "Dev mode"): registers a fresh node, draws events from a
 * {@link FakeServer} seeded with the node id, numbers each batch with a private {@link IdAssigner} and writes it
 * as one archive segment. A batch closes once it holds {@link DevRunSettings#batchEvents()} events and the next
 * event starts a new second (twice as many close it regardless), so no second is split over two segments in
 * practice. A failed batch is never retried: a segment write is not idempotent.
 *
 * <p>The events end {@link DevRunSettings#settleMillis()} before the run began and reach back the asked-for days
 * (never before the Hawkeye epoch). While the run goes, a fixed-delay task on the scheduler it is given tells the
 * listener its progress every {@link DevRunSettings#progressMillis()}; the task exists only while the run does,
 * and no progress call arrives after {@link #call()} returns.
 *
 * <p>Runs on one thread; {@link #cancel()} and {@link #progress()} may be called from any thread. The run stops
 * after its current batch when it is cancelled or dev mode is turned off.
 */
@Slf4j
public final class DevDataRun {

    /** How many generated events pass between two checks whether the run should stop. */
    public static final int CHECK_EVENTS = 4_096;

    private static final long DAY_MILLIS = 86_400_000L;

    private final @NotNull DevDataRequest request;
    private final @NotNull DevRunSettings settings;
    private final @NotNull DevDataStore store;
    private final @NotNull Shard home;
    private final @NotNull Clock clock;
    private final @NotNull Executor pack;
    private final @NotNull ScheduledExecutorService scheduler;
    private final @NotNull DevDataListener listener;
    private final @NotNull BooleanSupplier enabled;
    private final AtomicLong written = new AtomicLong();
    private final Object reportLock = new Object();
    private volatile boolean cancelled;
    private volatile @Nullable String nodeName;
    private volatile int players;
    private boolean reporting;

    /**
     * A run, not started.
     *
     * @param request   what to write
     * @param settings  what the run works with
     * @param store     the dev data storage
     * @param home      the home shard
     * @param clock     the clock
     * @param pack      where segment frames are compressed
     * @param scheduler where the progress task runs (the maintenance thread)
     * @param listener  hears how it goes
     * @param enabled   whether dev mode is still on
     */
    public DevDataRun(@NotNull DevDataRequest request, @NotNull DevRunSettings settings, @NotNull DevDataStore store,
            @NotNull Shard home, @NotNull Clock clock, @NotNull Executor pack,
            @NotNull ScheduledExecutorService scheduler, @NotNull DevDataListener listener,
            @NotNull BooleanSupplier enabled) {
        this.request = request;
        this.settings = settings;
        this.store = store;
        this.home = home;
        this.clock = clock;
        this.pack = pack;
        this.scheduler = scheduler;
        this.listener = listener;
        this.enabled = enabled;
    }

    /** Asks the run to stop after the batch it is on; what it wrote stays. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Where the run stands now.
     *
     * @return a fresh snapshot, or empty before its node is registered
     */
    public @NotNull Optional<DevDataProgress> progress() {
        String node = nodeName;
        return node == null ? Optional.empty() : Optional.of(progress(node));
    }

    /**
     * Runs on the calling thread.
     *
     * @return how it ended; every failure is an outcome, never an exception
     */
    public @NotNull DevDataOutcome call() {
        ServerNode node;
        try {
            Optional<ServerNode> registered = store.registerNode(home, settings.serverName(), settings.scope());
            if (registered.isEmpty()) {
                log.info("Dev mode: this database has no node id left for more fake data");
                return new DevDataOutcome.NoNodeLeft();
            }
            node = registered.get();
        } catch (SQLException | RuntimeException e) {
            return failed(e);
        }
        long end = settings.startedMillis() - settings.settleMillis();
        long start = Math.max(EventIds.EPOCH_MILLIS, settings.startedMillis() - request.days() * DAY_MILLIS);
        if (start >= end) {
            start = end - 1;
        }
        FakeServer server = new FakeServer(node.node(), request.count(), start, end, request.worlds());
        players = server.players();
        nodeName = node.name();
        log.info("Dev mode: generating {} fake event(s) over {} day(s) into node {} ({} fake players), started by {}",
                request.count(), request.days(), node.name(), server.players(), request.requestedBy());
        synchronized (reportLock) {
            reporting = true;
        }
        // Scheduled before the started call: its first run is a whole interval away, and a listener that holds
        // the run in started (a test) still hears progress.
        Optional<ScheduledFuture<?>> ticker = schedule();
        try {
            DevDataProgress first = progress(node.name());
            notify(() -> listener.started(first));
            return generate(server, node);
        } finally {
            synchronized (reportLock) {
                reporting = false;
            }
            ticker.ifPresent(task -> task.cancel(false));
        }
    }

    private @NotNull DevDataOutcome generate(@NotNull FakeServer server, @NotNull ServerNode node) {
        IdAssigner assigner = new IdAssigner();
        long count = request.count();
        long produced = 0;
        PendingEvent held = null;
        try {
            while (true) {
                List<PendingEvent> batch = new ArrayList<>(settings.batchEvents() + 1);
                if (held != null) {
                    batch.add(held);
                    held = null;
                }
                boolean stopping = false;
                while (produced < count) {
                    if (produced % CHECK_EVENTS == 0 && stopRequested()) {
                        stopping = true;
                        break;
                    }
                    PendingEvent next = server.next();
                    produced++;
                    if (closes(batch, next)) {
                        held = next;
                        break;
                    }
                    batch.add(next);
                }
                if (!batch.isEmpty()) {
                    assigner.add(batch);
                    List<PendingEvent> numbered = assigner.take(Long.MAX_VALUE / 2, true);
                    written.addAndGet(store.write(home, node, settings.scope(), numbered, pack));
                }
                if (produced >= count && held == null) {
                    DevDataProgress totals = progress(node.name());
                    log.info("Dev mode: finished generating: {} fake event(s) into node {} in {} s", totals.written(),
                            node.name(), Math.round(totals.elapsedMillis() / 1000.0));
                    return new DevDataOutcome.Finished(totals);
                }
                if (stopping || stopRequested()) {
                    DevDataProgress totals = progress(node.name());
                    log.info("Dev mode: stopped generating: {} of {} fake event(s) into node {} in {} s",
                            totals.written(), count, node.name(), Math.round(totals.elapsedMillis() / 1000.0));
                    return new DevDataOutcome.Cancelled(totals);
                }
            }
        } catch (SQLException | RuntimeException e) {
            return failed(e);
        }
    }

    /** Whether the next event closes the batch: between two seconds once it is full, or when twice full. */
    private boolean closes(@NotNull List<PendingEvent> batch, @NotNull PendingEvent next) {
        int size = batch.size();
        if (size >= 2 * settings.batchEvents()) {
            return true;
        }
        return size >= settings.batchEvents()
                && EventIds.stampSlot(next.stamp()) != EventIds.stampSlot(batch.getLast().stamp());
    }

    private boolean stopRequested() {
        return cancelled || !enabled.getAsBoolean();
    }

    private @NotNull Optional<ScheduledFuture<?>> schedule() {
        long every = Math.max(1, settings.progressMillis());
        try {
            return Optional.of(scheduler.scheduleWithFixedDelay(this::tick, every, every, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            log.debug("The maintenance executor is shut down; generating fake data without progress lines", e);
            return Optional.empty();
        }
    }

    /** One progress line, on the scheduler's thread; none once the run has ended. */
    private void tick() {
        synchronized (reportLock) {
            if (!reporting) {
                return;
            }
            Optional<DevDataProgress> now = progress();
            now.ifPresent(progress -> notify(() -> listener.progress(progress)));
        }
    }

    private @NotNull DevDataProgress progress(@NotNull String node) {
        return new DevDataProgress(node, request.count(), written.get(), players, request.days(),
                clock.millis() - settings.startedMillis());
    }

    private @NotNull DevDataOutcome failed(@NotNull Exception e) {
        log.warn("Generating fake data failed after {} event(s)", written.get(), e);
        return new DevDataOutcome.Failed(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
    }

    private static void notify(@NotNull Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("A dev data listener failed", e);
        }
    }
}
