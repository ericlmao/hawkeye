package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes Hawkeye's warnings, errors and crashes to text files in its data folder, so the console shows each
 * problem once. {@link #report} decides on the calling thread, without blocking or touching a file, whether a
 * problem is new in this run; its own thread writes the files. Two problems are the same when their
 * {@link Problem#fingerprint() fingerprints} match.
 *
 * <p>A problem's first time is written in full (message and stack trace) to the file of that minute, after the
 * server header; every repeat goes into that same file, one line each for the first {@link #REPEAT_LINES}, then
 * one line a minute that counts them. That bounds what a problem repeated every tick writes to about one line a
 * minute. If a file cannot be written, the text it would have held is logged to the console instead, and the error
 * log stops taking problems: they are logged as before.
 *
 * <p>Thread-safe: {@link #report} may be called from any thread (Folia region threads included).
 */
@Slf4j
public final class ErrorLog {

    /** Repeats of one problem written one by one before they are counted instead. */
    public static final int REPEAT_LINES = 100;

    /** How often counted repeats are written. */
    public static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(1);

    /** Different problems tracked in one run; any further one is logged as before. */
    public static final int MAX_TRACKED = 1000;

    private static final int QUEUE_CAPACITY = 10_000;
    private static final long NO_THREAD = -1;
    private static final long IN_FLIGHT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final @NotNull Path dataFolder;
    private final @NotNull Clock clock;
    private final @NotNull ReportFormat format;
    private final @NotNull Supplier<ServerSnapshot> server;
    private final @NotNull ReportListener listener;
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final AtomicInteger numbers = new AtomicInteger();
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    /** Calls between their {@link #open} check and their offer: close() waits for them before its Stop. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Hawkeye-ErrorLog");
        thread.setDaemon(true);
        return thread;
    });
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile boolean open;
    private volatile boolean broken;
    private volatile long startupThread = NO_THREAD;

    // Writer thread only.
    private final Set<Path> headed = new HashSet<>();
    private final Set<Tracked> counting = new LinkedHashSet<>();

    /**
     * An error log that is not started yet.
     *
     * @param dataFolder Hawkeye's data folder ({@code plugins/Hawkeye})
     * @param clock      the clock of counted repeats; its zone names the files
     * @param server     the server facts each file starts with, read on the writer thread
     * @param listener   told on the writer thread about each problem's first time once its file is written
     */
    public ErrorLog(@NotNull Path dataFolder, @NotNull Clock clock, @NotNull Supplier<ServerSnapshot> server,
                    @NotNull ReportListener listener) {
        this.dataFolder = dataFolder;
        this.clock = clock;
        this.format = new ReportFormat(clock.getZone());
        this.server = server;
        this.listener = listener;
    }

    /** Starts the writer thread; problems are taken from now on. */
    public void start() {
        open = true;
        writer.execute(this::run);
    }

    /**
     * Files a problem.
     *
     * @param problem the problem, copied out of its logging event
     * @return {@link ReportResult.First} the first time (show it once), {@link ReportResult.Repeat} after that
     *         (stay quiet), {@link ReportResult.Untracked} when the error log does not take it (log it as usual)
     */
    public @NotNull ReportResult report(@NotNull Problem problem) {
        if (broken) {
            return new ReportResult.Untracked("the error files cannot be written");
        }
        // Counted before open is read, so close() either makes this call see it closed or waits for its job.
        inFlight.incrementAndGet();
        try {
            if (!open) {
                return new ReportResult.Untracked("the error log is closed");
            }
            return file(problem);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private @NotNull ReportResult file(@NotNull Problem problem) {
        String key = problem.fingerprint();
        Tracked known = tracked.get(key);
        if (known == null) {
            if (tracked.size() >= MAX_TRACKED) {
                return new ReportResult.Untracked("more than " + MAX_TRACKED + " different problems");
            }
            // The first job is queued inside computeIfAbsent, so a repeat from another thread (which waits for
            // the mapping) is always queued after it.
            Tracked[] created = new Tracked[1];
            known = tracked.computeIfAbsent(key, ignored -> {
                Tracked fresh = new Tracked(format.file(dataFolder, problem.kind(), problem.timeMillis()),
                        problem.kind(), numbers.incrementAndGet());
                if (!queue.offer(new Job.First(fresh, problem))) {
                    return null;
                }
                created[0] = fresh;
                return fresh;
            });
            if (known == null) {
                dropped.incrementAndGet();
                return new ReportResult.Untracked("the error log is behind");
            }
            if (known == created[0]) {
                return new ReportResult.First(known.file, known.number);
            }
        }
        long occurrence = known.occurrences.incrementAndGet();
        if (!queue.offer(new Job.Repeat(known, problem, occurrence))) {
            dropped.incrementAndGet();
        }
        return new ReportResult.Repeat(known.file, known.number, occurrence);
    }

    /** Marks the calling thread as running Hawkeye's load or enable: what it logs at ERROR is a crash. */
    public void startupBegins() {
        startupThread = Thread.currentThread().threadId();
    }

    /** Ends {@link #startupBegins()}. */
    public void startupEnds() {
        startupThread = NO_THREAD;
    }

    /**
     * Whether a thread is running Hawkeye's load or enable now.
     *
     * @param threadId the thread's id
     * @return {@code true} while it is
     */
    public boolean isStartupThread(long threadId) {
        return threadId != NO_THREAD && threadId == startupThread;
    }

    /**
     * Completes once everything reported so far is written (counted repeats wait for their minute).
     *
     * @return the future; already complete when the error log is closed
     */
    public @NotNull CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (!offerWhileOpen(new Job.Flush(done))) {
            done.complete(null);
        }
        return done;
    }

    /**
     * Marks every waiting file reviewed ({@link ReportFiles#markReviewed}) on the writer thread, between two writes,
     * so no write races the move. Later repeats of a problem whose file moved go to the moved file and do not make
     * it wait again; a problem first logged afterwards starts a new waiting file.
     *
     * @param files the error and crash files
     * @return how many files were moved; when the error log is not running they are moved on the calling thread
     */
    public @NotNull CompletableFuture<Integer> markReviewed(@NotNull ReportFiles files) {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        if (!offerWhileOpen(new Job.Review(files, done))) {
            try {
                done.complete(files.markReviewed().size());
            } catch (IOException e) {
                done.completeExceptionally(e);
            }
        }
        return done;
    }

    /**
     * Queues a job unless the error log is closed, counted in {@link #inFlight} like a {@link #report}.
     *
     * @param job the job
     * @return {@code false} when it is closed or its queue is full
     */
    private boolean offerWhileOpen(@NotNull Job job) {
        inFlight.incrementAndGet();
        try {
            return open && queue.offer(job);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Stops taking problems, writes what is queued and the counted repeats, and stops the writer thread.
     *
     * @param wait how long to wait for the writer
     */
    public void close(@NotNull Duration wait) {
        if (!open) {
            writer.shutdown();
            return;
        }
        open = false;
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            // A report() past its open check was answered (or is about to be) First or Repeat, and the console
            // was told its file: its job goes ahead of the Stop. Such a call never blocks, so this wait is short.
            while (inFlight.get() > 0 && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(IN_FLIGHT_POLL_NANOS);
            }
            queue.put(new Job.Stop());
            stopped.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            writer.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdown();
        } catch (TimeoutException | ExecutionException e) {
            log.warn(LogMarkers.REPORTED, "The error log did not finish writing within {} s", wait.toSeconds());
            writer.shutdownNow();
        }
    }

    private void run() {
        try {
            boolean stopping = false;
            while (!stopping) {
                Job first = queue.poll(1, TimeUnit.SECONDS);
                List<Job> jobs = new ArrayList<>();
                if (first != null) {
                    jobs.add(first);
                    queue.drainTo(jobs);
                }
                stopping = process(jobs, false);
            }
            // close() waits for the calls past their open check before it queues the Stop; one that outlasted
            // that wait queued its job behind the Stop. It was answered First or Repeat (the console showed one
            // line or nothing), so it is written too.
            List<Job> late = new ArrayList<>();
            queue.drainTo(late);
            if (!late.isEmpty()) {
                process(late, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            broken = true;
            log.error(LogMarkers.REPORTED, "The error log stopped; problems are logged to the console as before", e);
        } finally {
            open = false;
            queue.forEach(job -> {
                if (job instanceof Job.Flush flush) {
                    flush.done().complete(null);
                } else if (job instanceof Job.Review review) {
                    review(review);
                }
            });
            stopped.complete(null);
        }
    }

    /**
     * Writes one batch of jobs.
     *
     * @param jobs     the jobs, oldest first
     * @param stopping whether the writer stops after them, so every counted repeat is written now
     * @return whether it stops: {@code stopping}, or a {@link Job.Stop} was among the jobs
     */
    private boolean process(@NotNull List<Job> jobs, boolean stopping) {
        boolean stop = stopping;
        Map<Path, StringBuilder> writes = new LinkedHashMap<>();
        List<CompletableFuture<Void>> flushed = new ArrayList<>();
        List<WrittenReport> firsts = new ArrayList<>();
        for (Job job : jobs) {
            switch (job) {
                case Job.First entry -> firsts.add(writeFirst(writes, entry));
                case Job.Repeat repeat -> writeRepeat(writes, repeat);
                case Job.Flush flush -> flushed.add(flush.done());
                case Job.Review review -> {
                    tell(firsts, write(writes));
                    writes.clear();
                    firsts.clear();
                    review(review);
                }
                case Job.Stop ignored -> stop = true;
            }
        }
        writeCounted(writes, stop);
        writeDropped(writes);
        tell(firsts, write(writes));
        flushed.forEach(done -> done.complete(null));
        return stop;
    }

    private @NotNull WrittenReport writeFirst(@NotNull Map<Path, StringBuilder> writes, @NotNull Job.First job) {
        Tracked problem = job.tracked();
        StringBuilder text = text(writes, problem.file, problem.kind, job.problem().timeMillis());
        text.append(format.entry(problem.number, job.problem()));
        return new WrittenReport(problem.number, problem.kind, job.problem().level(), job.problem().headline(),
                problem.file, job.problem().timeMillis());
    }

    private void tell(@NotNull List<WrittenReport> firsts, @NotNull Set<Path> failed) {
        firsts.stream().filter(report -> !failed.contains(report.file())).forEach(this::tell);
    }

    private void tell(@NotNull WrittenReport report) {
        try {
            listener.written(report);
        } catch (RuntimeException e) {
            log.warn(LogMarkers.REPORTED, "A report listener failed: {}", e.toString());
        }
    }

    private void review(@NotNull Job.Review job) {
        try {
            List<ReportFiles.Moved> moved = job.files().markReviewed();
            for (ReportFiles.Moved file : moved) {
                headed.add(file.to());
                for (Tracked problem : tracked.values()) {
                    if (problem.target().equals(file.from())) {
                        problem.reviewed = file.to();
                    }
                }
            }
            job.done().complete(moved.size());
        } catch (IOException | RuntimeException e) {
            job.done().completeExceptionally(e);
        }
    }

    private void writeRepeat(@NotNull Map<Path, StringBuilder> writes, @NotNull Job.Repeat job) {
        Tracked problem = job.tracked();
        if (problem.written < REPEAT_LINES) {
            problem.written++;
            text(writes, problem.target(), problem.kind, job.problem().timeMillis())
                    .append(format.repeat(problem.number, job.occurrence(), job.problem()));
            return;
        }
        if (problem.counted == 0) {
            problem.countedFrom = job.problem().timeMillis();
            counting.add(problem);
        }
        problem.counted++;
        problem.countedTo = job.problem().timeMillis();
        problem.countedTotal = job.occurrence();
        problem.last = job.problem();
    }

    private void writeCounted(@NotNull Map<Path, StringBuilder> writes, boolean all) {
        long now = clock.millis();
        for (var iterator = counting.iterator(); iterator.hasNext(); ) {
            Tracked problem = iterator.next();
            if (!all && now - problem.countedFrom < SUMMARY_INTERVAL.toMillis()) {
                continue;
            }
            Problem last = Objects.requireNonNull(problem.last);
            text(writes, problem.target(), problem.kind, now).append(format.summary(problem.number, problem.counted,
                    problem.countedFrom, problem.countedTo, problem.countedTotal, last));
            problem.counted = 0;
            iterator.remove();
        }
    }

    private void writeDropped(@NotNull Map<Path, StringBuilder> writes) {
        long count = dropped.getAndSet(0);
        if (count == 0) {
            return;
        }
        if (writes.isEmpty()) {
            dropped.addAndGet(count);
            return;
        }
        writes.values().iterator().next().append(format.dropped(count, clock.millis()));
    }

    private @NotNull StringBuilder text(@NotNull Map<Path, StringBuilder> writes, @NotNull Path file,
                                        @NotNull ProblemKind kind, long timeMillis) {
        return writes.computeIfAbsent(file, path -> {
            StringBuilder text = new StringBuilder();
            boolean exists = Files.exists(path);
            // Once per file and run, and again when the file was moved away (marked reviewed) meanwhile.
            if (headed.add(path) || !exists) {
                if (exists) {
                    text.append(System.lineSeparator());
                }
                text.append(header(kind, timeMillis));
            }
            return text;
        });
    }

    private @NotNull String header(@NotNull ProblemKind kind, long timeMillis) {
        try {
            return format.header(kind, server.get(), timeMillis);
        } catch (RuntimeException e) {
            return "Hawkeye " + kind.title() + " (the server facts could not be read: " + e + ")"
                    + System.lineSeparator() + System.lineSeparator();
        }
    }

    private @NotNull Set<Path> write(@NotNull Map<Path, StringBuilder> writes) {
        Set<Path> failed = new HashSet<>();
        for (Map.Entry<Path, StringBuilder> write : writes.entrySet()) {
            Path file = write.getKey();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, write.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                failed.add(file);
                // The console showed only a headline naming this file (or nothing, for a repeat): print what
                // the file would have held, so no message or stack trace is lost. Taking no further problems
                // bounds this to what was queued.
                if (!broken) {
                    broken = true;
                    log.error(LogMarkers.REPORTED, "Could not write {}; Hawkeye's problems are logged to the console "
                            + "as before: {}. What the file would have held:{}{}", file, e.toString(),
                            System.lineSeparator(), write.getValue());
                } else {
                    log.error(LogMarkers.REPORTED, "Could not write {} either; what it would have held:{}{}", file,
                            System.lineSeparator(), write.getValue());
                }
            }
        }
        return failed;
    }

    /** A problem seen in this run. The fields below {@link #occurrences} belong to the writer thread. */
    private static final class Tracked {

        private final @NotNull Path file;
        private final @NotNull ProblemKind kind;
        private final int number;
        private final AtomicLong occurrences = new AtomicLong(1);
        private int written;
        private long counted;
        private long countedFrom;
        private long countedTo;
        private long countedTotal;
        private @Nullable Problem last;
        /** Where its file went when it was marked reviewed; repeats follow it there. */
        private @Nullable Path reviewed;

        private Tracked(@NotNull Path file, @NotNull ProblemKind kind, int number) {
            this.file = file;
            this.kind = kind;
            this.number = number;
        }

        /** The file its repeats go to. */
        private @NotNull Path target() {
            return reviewed == null ? file : reviewed;
        }
    }

    /** Work for the writer thread. */
    private sealed interface Job {

        record First(@NotNull Tracked tracked, @NotNull Problem problem) implements Job {
        }

        record Repeat(@NotNull Tracked tracked, @NotNull Problem problem, long occurrence) implements Job {
        }

        record Flush(@NotNull CompletableFuture<Void> done) implements Job {
        }

        record Review(@NotNull ReportFiles files, @NotNull CompletableFuture<Integer> done) implements Job {
        }

        record Stop() implements Job {
        }
    }
}
