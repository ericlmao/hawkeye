package gg.moonrise.hawkeye.core.storage.archive;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.network.LeaseRepository;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.index.SegmentIndex;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the archive ({@link Archive#compact}) every {@code archive.interval-minutes} on the server that
 * holds the shard's {@code archive} lease, so a whole network can share one configuration. Each pass
 * packs one segment of {@code archive.segment-events} settled events (or fewer, once they are older
 * than {@code archive.after-hours}) on the query pool: it reads and compresses them without a lock, then
 * stores the segment and deletes them in one short transaction. Passes follow each other until one packs
 * nothing, so the maintenance thread (heartbeats, network jobs) is never held. After packing, a
 * run spends up to {@link #CATCH_UP_TIME} adding segments that are missing from the {@link SegmentIndex}
 * (written before it existed), newest first, a batch per transaction.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class Archiver implements Enableable, Disableable {

    /** The lease name in the {@code lease} table. */
    public static final String LEASE = "archive";

    /** The first run waits this long after enable. */
    public static final Duration FIRST_RUN = Duration.ofMinutes(2);

    /** How long one run may spend catching the segment index up. */
    public static final Duration CATCH_UP_TIME = Duration.ofSeconds(20);

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull StorageManager storage;
    private final @NotNull Archive archive;
    private final @NotNull LeaseRepository leases;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong archived = new AtomicLong();
    private volatile boolean held;
    private volatile boolean stopped;

    @Override
    public void onEnable() {
        long interval = TimeUnit.MINUTES.toSeconds(configProvider.get().archive().intervalMinutes());
        executors.maintenance().scheduleWithFixedDelay(this::tick, FIRST_RUN.toSeconds(), interval, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        stopped = true;
        if (held) {
            storage.readyNow().ifPresent(ready -> {
                try {
                    leases.release(ready.home().database(), LEASE, holder());
                } catch (SQLException | RuntimeException e) {
                    log.debug("Could not release the archive lease; it expires on its own", e);
                }
            });
            held = false;
        }
    }

    /**
     * Archives everything old enough now, when this server holds the lease.
     *
     * @return the number of events archived (0 when disabled, not ready, not the holder or busy)
     */
    public @NotNull CompletableFuture<Long> runOnce() {
        ArchiveSettings settings = configProvider.get().archive();
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (!settings.enabled() || ready.isEmpty() || stopped || !running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0L);
        }
        Shard home = ready.get().home();
        long ttl = leaseMillis(settings);
        try {
            held = leases.tryAcquire(home.database(), LEASE, holder(), clock.millis(), ttl);
        } catch (SQLException | RuntimeException e) {
            running.set(false);
            log.debug("Could not take the archive lease", e);
            return CompletableFuture.completedFuture(0L);
        }
        if (!held) {
            running.set(false);
            return CompletableFuture.completedFuture(0L);
        }
        return passes(home, settings, 0)
                .thenCompose(total -> catchUp(home, settings, clock.millis() + CATCH_UP_TIME.toMillis())
                        .thenApply(ignored -> total))
                .whenComplete((total, error) -> {
                    running.set(false);
                    if (error != null) {
                        log.warn("Archiving failed; it is retried on the next run", error);
                    } else if (total > 0) {
                        archived.addAndGet(total);
                        log.info("Archived {} event(s)", total);
                    }
                });
    }

    /**
     * Events this server archived since start.
     *
     * @return the count
     */
    public long archivedSinceStart() {
        return archived.get();
    }

    private @NotNull CompletableFuture<Long> passes(@NotNull Shard home, @NotNull ArchiveSettings settings,
            long total) {
        if (stopped) {
            return CompletableFuture.completedFuture(total);
        }
        CompletableFuture<Integer> pass;
        try {
            pass = CompletableFuture.supplyAsync(() -> compact(home, settings), executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(total);
        }
        return pass.thenCompose(moved -> moved == 0 ? CompletableFuture.completedFuture(total)
                : passes(home, settings, total + moved));
    }

    /** Indexes missing segments, a batch per task, until none is missing or the time is up. */
    private @NotNull CompletableFuture<Void> catchUp(@NotNull Shard home, @NotNull ArchiveSettings settings,
            long untilMillis) {
        if (stopped || clock.millis() >= untilMillis) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<SegmentIndex.CatchUp> batch;
        try {
            batch = CompletableFuture.supplyAsync(() -> catchUpIndex(home, settings), executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(null);
        }
        return batch.thenCompose(result -> result.more() && result.indexed() > 0
                ? catchUp(home, settings, untilMillis) : CompletableFuture.completedFuture(null));
    }

    private @NotNull SegmentIndex.CatchUp catchUpIndex(@NotNull Shard home, @NotNull ArchiveSettings settings) {
        try {
            if (!leases.tryAcquire(home.database(), LEASE, holder(), clock.millis(), leaseMillis(settings))) {
                held = false;
                return new SegmentIndex.CatchUp(0, false);
            }
            SegmentIndex.CatchUp result = archive.catchUpIndex(home, SegmentIndex.CATCH_UP_BATCH);
            if (result.indexed() > 0) {
                log.debug("Added {} segment(s) to the segment index; more missing: {}", result.indexed(), result.more());
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("segment index catch-up failed: " + e.getMessage(), e);
        }
    }

    /** One pass, after renewing the lease: a backlog can take longer than the lease lasts. */
    private int compact(@NotNull Shard home, @NotNull ArchiveSettings settings) {
        try {
            if (!leases.tryAcquire(home.database(), LEASE, holder(), clock.millis(), leaseMillis(settings))) {
                held = false;
                return 0;
            }
            return archive.compact(home, settings, clock.millis());
        } catch (SQLException e) {
            throw new IllegalStateException("archive pass failed: " + e.getMessage(), e);
        }
    }

    private static long leaseMillis(@NotNull ArchiveSettings settings) {
        return Duration.ofMinutes(settings.intervalMinutes()).multipliedBy(3).toMillis();
    }

    private void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Archiving failed", e);
        }
    }

    private @NotNull String holder() {
        return configProvider.get().server().name();
    }
}
