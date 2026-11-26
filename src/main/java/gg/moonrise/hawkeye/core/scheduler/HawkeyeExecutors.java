package gg.moonrise.hawkeye.core.scheduler;

import gg.moonrise.hawkeye.util.log.LogMarkers;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * The plugin's own threads for database work, so nothing blocking runs on a server thread or the
 * common pool. Both pools are bounded; a full query pool rejects work (the caller reports "busy")
 * instead of queueing without limit. Shut down when the Spring context closes, after every
 * {@code Disableable} has run.
 */
@Slf4j
@SpringComponent
public final class HawkeyeExecutors {

    private static final int QUERY_THREADS = 4;
    private static final int QUERY_QUEUE = 256;

    private final ThreadPoolExecutor query = new ThreadPoolExecutor(QUERY_THREADS, QUERY_THREADS, 60,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUERY_QUEUE), threads("Hawkeye-Query"));
    private final ScheduledExecutorService maintenance =
            Executors.newSingleThreadScheduledExecutor(threads("Hawkeye-Maintenance"));

    /**
     * The pool for lookups, rollback planning, purges and job handling.
     *
     * @return the query executor
     */
    public @NotNull ExecutorService query() {
        return query;
    }

    /**
     * One thread for timed housekeeping: connecting, heartbeats, job polling, auto-purge.
     *
     * @return the maintenance executor
     */
    public @NotNull ScheduledExecutorService maintenance() {
        return maintenance;
    }

    /** Stops both pools, waiting briefly for running work. */
    @PreDestroy
    public void shutdown() {
        maintenance.shutdownNow();
        query.shutdown();
        try {
            if (!query.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Query work still running after 5 s; interrupting it");
                query.shutdownNow();
            }
        } catch (InterruptedException e) {
            query.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A factory of named daemon threads.
     *
     * @param prefix the thread name prefix
     * @return the factory
     */
    public static @NotNull ThreadFactory threads(@NotNull String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, error) ->
                    log.error(LogMarkers.CRASH, "Uncaught exception on {}", failed.getName(), error));
            return thread;
        };
    }
}
