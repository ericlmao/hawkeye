package gg.moonrise.hawkeye.core.queue.event;

import gg.moonrise.hawkeye.config.file.WriterSettings;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.queue.id.IdAssigner;
import gg.moonrise.hawkeye.core.queue.spool.EventSpool;
import gg.moonrise.hawkeye.core.queue.spool.SpoolReplay;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.core.time.EventClock;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.storage.WriterStats;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single writer thread. It sleeps until a batch is full, the flush interval passes or someone
 * asks for a flush, then writes what is ready in batches of {@code writer.batch-size}.
 *
 * <p>Captured events pass through an {@link IdAssigner} first, which gives every event of a second
 * that second and its rank in the fixed order the archive reproduces (docs/architecture.md, "Event
 * id"). A second is written once it has closed; a flush request and shutdown take every second at
 * once, so nothing captured before them is held back. A failed batch already carries its final stamps
 * and is retried unchanged with back-off (the queue keeps accepting events up to its capacity), so a
 * database restart loses nothing and a retry writes the same ids. On disable it drains the queue for
 * up to {@code writer.shutdown-timeout-seconds} and spools the rest, numbered, to disk; the next start
 * writes the spool as it is ({@link SpoolReplay}).
 *
 * <p>Before the first event is numbered, the assigner's floor is set past the newest slot already in
 * the home database or the spool, so a restart or a clock that stepped back never reuses a rank, and
 * new events never share a second with spooled ones. That is why captured events do not wait for the
 * spool: a file that cannot be written yet is tried again later while logging goes on.
 *
 * <p>{@link #flush()} gives read-your-writes: lookups call it first, so they see every event captured
 * before the command.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class EventWriter implements Enableable, Disableable {

    private static final long MIN_RETRY_MILLIS = 250;

    /** The fewest events the assigner may hold before the writer stops draining the queue into it. */
    private static final int MIN_HELD = 32_768;
    private static final long SPOOL_RETRY_MILLIS = 30_000;

    private final @NotNull EventQueue queue;
    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull EventSpool spool;
    private final @NotNull SpoolReplay spoolReplay;
    private final @NotNull EventClock clock;
    private final @NotNull HawkeyeConfigProvider configProvider;

    private final ExecutorService thread = Executors.newSingleThreadExecutor(HawkeyeExecutors.threads("Hawkeye-Writer"));
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> flushRequests = new ConcurrentLinkedQueue<>();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong spooled = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lastFlushMillis = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final IdAssigner assigner = new IdAssigner();
    /** Events with final stamps, waiting to be written; the head is the batch being retried. */
    private final ArrayDeque<PendingEvent> numbered = new ArrayDeque<>();
    private volatile boolean running;
    private volatile int numberedCount;
    private int consecutiveFailures;
    private @Nullable Failure lastFailure;
    private boolean spoolReplayed;
    private boolean spoolScanned;
    private long spoolRetryAtMillis;
    private boolean floorSet;

    @Override
    public void onEnable() {
        running = true;
        thread.execute(this::run);
    }

    @Override
    public void onDisable() {
        running = false;
        queue.wake();
        int timeout = configProvider.get().writer().shutdownTimeoutSeconds();
        try {
            stopped.get(timeout + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("The writer did not stop in time; queued events may be lost", e);
        }
        thread.shutdownNow();
    }

    /**
     * Writes everything queued so far, including events of the current second.
     *
     * @return completes when every event queued before the call is written, or the attempt failed
     *         (the events then stay queued and the caller reads what is already stored)
     */
    public @NotNull CompletableFuture<Void> flush() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> request = new CompletableFuture<>();
        flushRequests.add(request);
        if (!running) {
            // The loop ended between the check and the add and may have released its last requests.
            request.complete(null);
        }
        queue.wake();
        return request;
    }

    /**
     * The writer's counters.
     *
     * @return a snapshot
     */
    public @NotNull WriterStats stats() {
        return new WriterStats(queue.size() + assigner.pending() + numberedCount, written.get(), batches.get(),
                queue.dropped(), spooled.get(), failures.get(), lastFlushMillis.get(), lastError.get());
    }

    private void run() {
        try {
            while (running) {
                queue.awaitWork(settings().flushIntervalMillis());
                if (!running) {
                    break;
                }
                writeOnce();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error(LogMarkers.CRASH, "The writer loop failed and stopped", e);
        } finally {
            // From here on flush() answers at once: nothing would complete a request queued later.
            running = false;
            shutdownDrain();
            release(takeRequests());
            stopped.complete(null);
        }
    }

    /**
     * One pass of the loop. An unexpected failure is counted and backed off like a failed batch, so it
     * never ends the writer while events are still captured and flushes still wait for it.
     */
    private void writeOnce() throws InterruptedException {
        List<CompletableFuture<Void>> requests = takeRequests();
        try {
            writeAvailable(!requests.isEmpty());
        } catch (RuntimeException e) {
            if (countFailure(Failure.LOOP, e)) {
                log.error("The writer loop failed (attempt {}), retrying", consecutiveFailures, e);
            }
            release(requests);
            backOff();
            return;
        }
        release(requests);
    }

    /**
     * Writes the spool, then every numbered event, until nothing is ready or a batch fails.
     *
     * @param all {@code true} to number open seconds too (a flush request)
     */
    private void writeAvailable(boolean all) throws InterruptedException {
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return;
        }
        if (!floorSet && !establishFloor(ready.get())) {
            backOff();
            return;
        }
        replaySpool(ready.get());
        while (running) {
            if (numbered.isEmpty()) {
                boolean full = fill();
                number(assigner.take(clock.millis(), all || full));
            }
            if (numbered.isEmpty()) {
                return;
            }
            if (!writeNumbered(ready.get())) {
                backOff();
                return;
            }
        }
    }

    /**
     * Drains the queue into the assigner while it holds fewer than its limit.
     *
     * @return {@code true} when the assigner is full, so open seconds must be taken to make room
     */
    private boolean fill() {
        int limit = Math.max(16 * settings().batchSize(), MIN_HELD);
        int room = limit - assigner.pending();
        if (room > 0) {
            assigner.add(queue.drain(room));
        }
        return assigner.pending() >= limit;
    }

    private void number(@NotNull List<PendingEvent> events) {
        numbered.addAll(events);
        numberedCount = numbered.size();
    }

    /**
     * Sets the assigner's floor past the newest slot already used: in the home database or the spool.
     *
     * @return {@code false} when the database could not be read
     */
    private boolean establishFloor(@NotNull StorageState.Ready ready) {
        if (!spoolScanned) {
            // Spool files not replayed yet hold ranks too, whether or not they can be written now.
            spoolReplay.scanNewestSlot();
            spoolScanned = true;
        }
        long newestId;
        try {
            newestId = store.newestId(ready.home());
        } catch (SQLException | RuntimeException e) {
            if (countFailure(Failure.NEWEST_ID, e)) {
                log.warn("Could not read the newest stored id, keeping captured events queued (attempt {}): {}",
                        consecutiveFailures, lastError.get());
            }
            return false;
        }
        long newestSlot = newestId == 0 ? -1 : EventIds.slot(newestId);
        assigner.floor(Math.max(newestSlot, spoolReplay.newestSlot()) + 1);
        floorSet = true;
        return true;
    }

    /**
     * Writes the spool files of an earlier run. Captured events do not wait for them: the floor already
     * lies past every spooled slot, so the two never share an id, and a file that cannot be written
     * (it is tried again every {@link #SPOOL_RETRY_MILLIS}) must not stop the logging of new events.
     */
    private void replaySpool(@NotNull StorageState.Ready ready) {
        if (spoolReplayed || clock.millis() < spoolRetryAtMillis) {
            return;
        }
        spoolReplayed = spoolReplay.replay(batch -> write(ready, batch), settings().batchSize());
        if (!spoolReplayed) {
            spoolRetryAtMillis = clock.millis() + SPOOL_RETRY_MILLIS;
        }
    }

    /** Writes the numbered events in batches; a failed batch stays at the head to be retried unchanged. */
    private boolean writeNumbered(@NotNull StorageState.Ready ready) {
        while (!numbered.isEmpty()) {
            int size = Math.min(settings().batchSize(), numbered.size());
            List<PendingEvent> batch = new ArrayList<>(size);
            Iterator<PendingEvent> events = numbered.iterator();
            while (batch.size() < size) {
                batch.add(events.next());
            }
            if (!write(ready, batch)) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                numbered.removeFirst();
            }
            numberedCount = numbered.size();
        }
        return true;
    }

    private boolean write(@NotNull StorageState.Ready ready, @NotNull List<PendingEvent> batch) {
        long start = System.nanoTime();
        try {
            store.write(ready.home(), ready.node(), storage.worldScope(), batch);
            written.addAndGet(batch.size());
            batches.incrementAndGet();
            lastFlushMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            consecutiveFailures = 0;
            return true;
        } catch (SQLException | RuntimeException e) {
            if (countFailure(Failure.WRITE, e)) {
                log.warn("Could not write {} event(s), keeping them queued (attempt {}): {}", batch.size(),
                        consecutiveFailures, lastError.get());
            }
            return false;
        }
    }

    /**
     * Counts a failure for the stats and the back-off. Each caller logs with a format string of its own:
     * the error log tells problems apart by it.
     *
     * @param failure where it failed
     * @param e       what it threw
     * @return {@code true} when this attempt is to be logged: the first, one that failed elsewhere than the
     *         attempt before (so a second kind of failure is shown at once), then every 20th
     */
    private boolean countFailure(@NotNull Failure failure, @NotNull Exception e) {
        failures.incrementAndGet();
        consecutiveFailures++;
        lastError.set(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        boolean changed = failure != lastFailure;
        lastFailure = failure;
        return changed || consecutiveFailures == 1 || consecutiveFailures % 20 == 0;
    }

    private void backOff() throws InterruptedException {
        long max = TimeUnit.SECONDS.toMillis(settings().maxRetryDelaySeconds());
        long delay = Math.min(max, MIN_RETRY_MILLIS << Math.min(consecutiveFailures, 10));
        // Anyone waiting on a flush reads what is stored rather than waiting out the outage.
        release(takeRequests());
        queue.awaitWork(delay);
    }

    private void shutdownDrain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings().shutdownTimeoutSeconds());
        Optional<StorageState.Ready> ready = storage.readyNow();
        boolean healthy = ready.isPresent();
        if (!floorSet) {
            // The spool files not replayed yet hold ranks too; what this run spools must come after them,
            // also when the database (and with it the stored newest id) was never reached.
            healthy = healthy && establishFloor(ready.get());
            assigner.floor(spoolReplay.scanNewestSlot() + 1);
        }
        while (healthy && System.nanoTime() < deadline) {
            if (numbered.isEmpty()) {
                fill();
                number(assigner.take(clock.millis(), true));
            }
            if (numbered.isEmpty()) {
                break;
            }
            healthy = writeNumbered(ready.get());
        }
        assigner.add(queue.drain(Integer.MAX_VALUE));
        number(assigner.take(clock.millis(), true));
        List<PendingEvent> left = new ArrayList<>(numbered);
        numbered.clear();
        numberedCount = 0;
        if (left.isEmpty()) {
            return;
        }
        try {
            Path file = spool.write(left);
            spooled.addAndGet(left.size());
            log.warn("Spooled {} unwritten event(s) to {}; they are written on the next start", left.size(),
                    file.getFileName());
        } catch (IOException e) {
            log.error("Could not spool {} unwritten event(s); they are lost", left.size(), e);
        }
    }

    /**
     * Completes flush requests. A caller's dependent stage runs here, on the writer thread, and may throw
     * (an executor that rejects its task); that must never stop the writer.
     */
    private static void release(@NotNull List<CompletableFuture<Void>> requests) {
        for (CompletableFuture<Void> request : requests) {
            try {
                request.complete(null);
            } catch (RuntimeException e) {
                log.warn("A flush waiter failed on the writer thread", e);
            }
        }
    }

    private @NotNull List<CompletableFuture<Void>> takeRequests() {
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        CompletableFuture<Void> request;
        while ((request = flushRequests.poll()) != null) {
            requests.add(request);
        }
        return requests;
    }

    private @NotNull WriterSettings settings() {
        return configProvider.get().writer();
    }

    /** Where an attempt failed; each logs with its own format string. */
    private enum Failure {
        LOOP,
        NEWEST_ID,
        WRITE
    }
}
