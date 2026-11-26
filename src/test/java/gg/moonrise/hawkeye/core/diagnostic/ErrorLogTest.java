package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ErrorLogTest {

    /** 2026-11-25 14:03:05 UTC. */
    private static final long AT_14_03_05 = Instant.parse("2026-11-25T14:03:05Z").toEpochMilli();

    private static final ServerSnapshot SERVER = new ServerSnapshot("1.21.1", "Paper", "Paper",
            "1.21.1-133-a1b2c3d (MC: 1.21.1)", "21.0.5 (Eclipse Adoptium, OpenJDK 64-Bit Server VM)",
            "Linux 6.8 (amd64)", "26.4", List.of(new ServerSnapshot.PluginLine("CoreProtect", "25.0", true),
            new ServerSnapshot.PluginLine("Hawkeye", "26.4", true),
            new ServerSnapshot.PluginLine("LuckPerms", "5.4.141", false)));

    @TempDir
    private Path folder;

    private final List<WrittenReport> written = new CopyOnWriteArrayList<>();
    private ErrorLog errorLog;

    @BeforeEach
    public void setUp() {
        errorLog = new ErrorLog(folder, Clock.fixed(Instant.ofEpochMilli(AT_14_03_05), ZoneOffset.UTC), () -> SERVER,
                written::add);
        errorLog.start();
    }

    @AfterEach
    public void tearDown() {
        errorLog.close(Duration.ofSeconds(5));
    }

    @Test
    public void aProblemIsWrittenToTheFileOfItsMinuteAfterTheServerHeader() throws IOException {
        ReportResult result = errorLog.report(problem(AT_14_03_05, "Could not write 5 event(s) (attempt 1)",
                new SQLException("database is locked")));

        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        assertEquals(new ReportResult.First(file, 1), result);
        String text = close(file);
        assertTrue(text.startsWith("Hawkeye error report, started 2026-11-25 14:03:05.000 +00:00"), text);
        assertContainsInOrder(text, "Server", "  Minecraft version: 1.21.1", "  Server type: Paper",
                "  Server: Paper 1.21.1-133-a1b2c3d (MC: 1.21.1)",
                "  Java: 21.0.5 (Eclipse Adoptium, OpenJDK 64-Bit Server VM)", "  Operating system: Linux 6.8 (amd64)",
                "  Hawkeye: 26.4", "Plugins (3)", "  CoreProtect 25.0", "  Hawkeye 26.4",
                "  LuckPerms 5.4.141 (not enabled)",
                "#1 WARN at 2026-11-25 14:03:05.000 +00:00", "Logger: gg.moonrise.hawkeye.core.queue.event.EventWriter",
                "Thread: Hawkeye-Writer", "Could not write 5 event(s) (attempt 1)",
                "java.sql.SQLException: database is locked",
                "\tat gg.moonrise.hawkeye.core.diagnostic.ErrorLogTest.");
    }

    @Test
    public void repeatsGoToTheFileOfTheFirstTimeAsOneLineEach() throws IOException {
        errorLog.report(problem(AT_14_03_05, "Could not write 5 event(s) (attempt 1)", new SQLException("locked")));
        long later = AT_14_03_05 + TimeUnit.MINUTES.toMillis(7);
        ReportResult second = errorLog.report(problem(later, "Could not write 9 event(s) (attempt 2)",
                new SQLException("busy")));
        ReportResult third = errorLog.report(problem(later + 1000, "Could not write 9 event(s) (attempt 3)",
                new SQLException("busy")));

        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        assertEquals(new ReportResult.Repeat(file, 1, 2), second);
        assertEquals(new ReportResult.Repeat(file, 1, 3), third);
        assertFalse(Files.exists(folder.resolve("errors").resolve("Error-2026-11-25-14-10.txt")));
        String text = close(file);
        assertEquals(1, count(text, "java.sql.SQLException"), "the stack trace is written once");
        assertContainsInOrder(text,
                "[2026-11-25 14:10:05.000 +00:00] #1 again (2 times): Could not write 9 event(s) (attempt 2): "
                        + "SQLException: busy",
                "[2026-11-25 14:10:06.000 +00:00] #1 again (3 times): Could not write 9 event(s) (attempt 3): "
                        + "SQLException: busy");
    }

    @Test
    public void problemsFirstLoggedInTheSameMinuteShareAFile() throws IOException {
        ReportResult first = errorLog.report(problem(AT_14_03_05, "Could not write", null));
        ReportResult second = errorLog.report(new Problem(AT_14_03_05 + 50_000, ProblemKind.ERROR, "ERROR",
                "gg.moonrise.hawkeye.core.storage.archive.Archiver", "Hawkeye-Maintenance-1", "Archiving failed",
                "Archiving failed", new IllegalStateException("full")));

        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        assertEquals(new ReportResult.First(file, 1), first);
        assertEquals(new ReportResult.First(file, 2), second);
        String text = close(file);
        assertEquals(1, count(text, "Hawkeye error report"), "one header per file");
        assertContainsInOrder(text, "#1 WARN at 2026-11-25 14:03:05.000 +00:00",
                "#2 ERROR at 2026-11-25 14:03:55.000 +00:00",
                "Logger: gg.moonrise.hawkeye.core.storage.archive.Archiver", "java.lang.IllegalStateException: full");
    }

    @Test
    public void aProblemFirstLoggedInTheNextMinuteStartsAnotherFile() {
        errorLog.report(problem(AT_14_03_05, "Could not write", null));
        ReportResult next = errorLog.report(new Problem(AT_14_03_05 + 60_000, ProblemKind.ERROR, "ERROR",
                "gg.moonrise.hawkeye.Other", "main", "Other", "Other", null));

        assertEquals(new ReportResult.First(folder.resolve("errors").resolve("Error-2026-11-25-14-04.txt"), 2), next);
        errorLog.close(Duration.ofSeconds(5));
        assertTrue(Files.isRegularFile(folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt")));
        assertTrue(Files.isRegularFile(folder.resolve("errors").resolve("Error-2026-11-25-14-04.txt")));
    }

    @Test
    public void aCrashGoesToTheCrashFolder() throws IOException {
        ReportResult result = errorLog.report(new Problem(AT_14_03_05, ProblemKind.CRASH, "ERROR",
                "gg.moonrise.hawkeye.HawkeyePlugin", "Server thread", "Hawkeye could not {} and is disabled",
                "Hawkeye could not load and is disabled", new IllegalStateException("broken settings")));

        Path file = folder.resolve("crash").resolve("Crash-2026-11-25-14-03.txt");
        assertEquals(new ReportResult.First(file, 1), result);
        String text = close(file);
        assertTrue(text.startsWith("Hawkeye crash report, started 2026-11-25 14:03:05.000 +00:00"), text);
        assertContainsInOrder(text, "  Minecraft version: 1.21.1", "Plugins (3)", "#1 ERROR",
                "Hawkeye could not load and is disabled", "java.lang.IllegalStateException: broken settings");
        assertFalse(Files.exists(folder.resolve("errors")));
    }

    @Test
    public void repeatsPastTheLimitAreCountedOnOneLine() throws IOException {
        int repeats = ErrorLog.REPEAT_LINES + 50;
        for (int i = 0; i <= repeats; i++) {
            errorLog.report(problem(AT_14_03_05 + i, "Could not write (attempt " + i + ")", null));
        }

        String text = close(folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt"));
        assertEquals(ErrorLog.REPEAT_LINES, count(text, "#1 again ("));
        assertContainsInOrder(text, "#1 again (" + (ErrorLog.REPEAT_LINES + 1) + " times)",
                "#1 again 50 more times from 14:03:05 to 14:03:05 (" + (repeats + 1) + " times); the last: "
                        + "Could not write (attempt " + repeats + ")");
    }

    @Test
    public void aFileLeftByAnEarlierRunGetsThisRunsHeader() throws IOException {
        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "earlier run" + System.lineSeparator());

        errorLog.report(problem(AT_14_03_05, "Could not write", null));

        String text = close(file);
        assertTrue(text.startsWith("earlier run"), text);
        assertContainsInOrder(text, "earlier run", "Hawkeye error report", "#1 WARN");
    }

    @Test
    public void theListenerHearsOfEachFirstTimeOnceItsFileIsWritten() throws Exception {
        errorLog.report(problem(AT_14_03_05, "Could not write (attempt 1)", new SQLException("locked")));
        errorLog.report(problem(AT_14_03_05 + 1000, "Could not write (attempt 2)", new SQLException("locked")));
        errorLog.report(new Problem(AT_14_03_05, ProblemKind.CRASH, "ERROR", "gg.moonrise.hawkeye.HawkeyePlugin",
                "Server thread", "Hawkeye could not {}", "Hawkeye could not load", null));
        errorLog.flush().get(5, TimeUnit.SECONDS);

        assertEquals(2, written.size(), "repeats are not told: " + written);
        WrittenReport error = written.getFirst();
        assertEquals(new WrittenReport(1, ProblemKind.ERROR, "WARN",
                "Could not write (attempt 1): SQLException: locked",
                folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt"), AT_14_03_05), error);
        assertTrue(Files.isRegularFile(error.file()), "the file exists when the listener hears of it");
        assertEquals(ProblemKind.CRASH, written.get(1).kind());
    }

    @Test
    public void aFileMovedAwayStartsAgainWithItsHeader() throws Exception {
        errorLog.report(problem(AT_14_03_05, "Could not write", null));
        errorLog.flush().get(5, TimeUnit.SECONDS);
        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        Files.move(file, folder.resolve("moved.txt"));

        errorLog.report(problem(AT_14_03_05 + 5000, "Could not write again", null));

        String text = close(file);
        assertTrue(text.startsWith("Hawkeye error report"), text);
        assertContainsInOrder(text, "  Minecraft version: 1.21.1", "#1 again (2 times): Could not write again");
    }

    @Test
    public void repeatsOfAReviewedProblemFollowItsFileAndANewProblemWaits() throws Exception {
        errorLog.report(problem(AT_14_03_05, "Could not write (attempt 1)", null));
        Path waiting = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        Path reviewed = folder.resolve("errors").resolve(ReportFiles.REVIEWED).resolve("Error-2026-11-25-14-03.txt");

        assertEquals(1, errorLog.markReviewed(new ReportFiles(folder)).get(5, TimeUnit.SECONDS));
        errorLog.report(problem(AT_14_03_05 + 1000, "Could not write (attempt 2)", null));
        errorLog.flush().get(5, TimeUnit.SECONDS);

        assertFalse(Files.exists(waiting), "a repeat does not make the reviewed problem wait again");
        String moved = Files.readString(reviewed).replace(System.lineSeparator(), "\n");
        assertEquals(1, count(moved, "Hawkeye error report"), "no second header in the reviewed file");
        assertContainsInOrder(moved, "#1 WARN", "#1 again (2 times): Could not write (attempt 2)");

        errorLog.report(new Problem(AT_14_03_05 + 2000, ProblemKind.ERROR, "ERROR", "gg.moonrise.hawkeye.Other",
                "main", "Something new", "Something new", null));
        String text = close(waiting);
        assertTrue(text.startsWith("Hawkeye error report"), text);
        assertContainsInOrder(text, "#2 ERROR", "Something new");
        assertFalse(text.contains("again"), text);
    }

    @Test
    public void aClosedLogTakesNoProblem() {
        errorLog.close(Duration.ofSeconds(5));

        ReportResult result = errorLog.report(problem(AT_14_03_05, "Could not write", null));

        assertInstanceOf(ReportResult.Untracked.class, result);
    }

    @Test
    public void theLogTracksALimitedNumberOfDifferentProblems() {
        for (int i = 0; i < ErrorLog.MAX_TRACKED; i++) {
            assertInstanceOf(ReportResult.First.class, errorLog.report(new Problem(AT_14_03_05, ProblemKind.ERROR,
                    "WARN", "gg.moonrise.hawkeye.Test", "main", "Problem " + i, "Problem " + i, null)));
        }

        assertInstanceOf(ReportResult.Untracked.class, errorLog.report(new Problem(AT_14_03_05, ProblemKind.ERROR,
                "WARN", "gg.moonrise.hawkeye.Test", "main", "One more", "One more", null)));
        assertInstanceOf(ReportResult.Repeat.class, errorLog.report(new Problem(AT_14_03_05, ProblemKind.ERROR,
                "WARN", "gg.moonrise.hawkeye.Test", "main", "Problem 0", "Problem 0", null)));
    }

    @Test
    public void manyThreadsReportingOneProblemGetOneFirst() throws InterruptedException {
        int threads = 8;
        int each = 50;
        List<ReportResult> results = Collections.synchronizedList(new ArrayList<>());
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    results.add(errorLog.report(problem(AT_14_03_05, "Could not write", null)));
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(1, results.stream().filter(ReportResult.First.class::isInstance).count());
        assertEquals((long) threads * each - 1, results.stream().filter(ReportResult.Repeat.class::isInstance).count());
        assertEquals((long) threads * each, results.stream().filter(ReportResult.Repeat.class::isInstance)
                .map(ReportResult.Repeat.class::cast).mapToLong(ReportResult.Repeat::occurrence).max().orElse(0));
    }

    @Test
    public void whatAFileCannotTakeIsLoggedToTheConsole() throws Exception {
        Files.writeString(folder.resolve("errors"), "a file where the folder belongs");
        CountDownLatch inHeader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ErrorLog failing = started(heldOnce(inHeader, release));
        try (ConsoleLines console = ConsoleLines.attach()) {
            ReportResult first = failing.report(problem(AT_14_03_05, "Could not write 5 event(s) (attempt 1)",
                    new SQLException("database is locked")));
            assertTrue(inHeader.await(5, TimeUnit.SECONDS), "the writer holds the first problem");
            ReportResult next = failing.report(new Problem(AT_14_03_05 + 60_000, ProblemKind.ERROR, "ERROR",
                    "gg.moonrise.hawkeye.Other", "main", "Archiving failed", "Archiving failed",
                    new IllegalStateException("full")));
            release.countDown();
            failing.close(Duration.ofSeconds(5));

            assertInstanceOf(ReportResult.First.class, first);
            assertInstanceOf(ReportResult.First.class, next, "queued before the first write failed");
            List<String> lines = console.of(ErrorLog.class.getName()).stream()
                    .map(event -> event.getMessage().getFormattedMessage().replace(System.lineSeparator(), "\n"))
                    .toList();
            assertEquals(2, lines.size(), lines.toString());
            assertContainsInOrder(lines.get(0), "Could not write ", "Error-2026-11-25-14-03.txt",
                    "What the file would have held:\nHawkeye error report", "#1 WARN",
                    "Could not write 5 event(s) (attempt 1)", "java.sql.SQLException: database is locked",
                    "\tat gg.moonrise.hawkeye.core.diagnostic.ErrorLogTest.");
            assertContainsInOrder(lines.get(1), "Error-2026-11-25-14-04.txt either", "#2 ERROR", "Archiving failed",
                    "java.lang.IllegalStateException: full");
            assertTrue(written.isEmpty(), "the listener hears of no file that was not written: " + written);
        }
    }

    @Test
    public void aReportInFlightWhenCloseBeginsIsWritten() throws Exception {
        errorLog.report(problem(AT_14_03_05, "Could not write (attempt 1)", null));
        errorLog.flush().get(5, TimeUnit.SECONDS);

        // A report past its open check when close() begins, held there until close() has started.
        CountDownLatch inReport = new CountDownLatch(1);
        CountDownLatch finishReport = new CountDownLatch(1);
        AtomicReference<ReportResult> late = new AtomicReference<>();
        Thread reporter = new Thread(() -> late.set(errorLog.report(new Problem(AT_14_03_05 + 1000,
                ProblemKind.ERROR, "ERROR", "gg.moonrise.hawkeye.Other", "main", "Logged during shutdown",
                "Logged during shutdown", new HeldCause(inReport, finishReport)))));
        reporter.start();
        assertTrue(inReport.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> errorLog.close(Duration.ofSeconds(10)));
        closer.start();
        for (long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                closer.getState() != Thread.State.TIMED_WAITING && closer.isAlive() && System.nanoTime() < until; ) {
            Thread.sleep(5);
        }
        // Were the Stop queued now, the writer would end before the report queues its job.
        closer.join(300);
        assertTrue(closer.isAlive(), "close() waits for the report in flight");
        assertInstanceOf(ReportResult.Untracked.class, errorLog.report(new Problem(AT_14_03_05, ProblemKind.ERROR,
                "WARN", "gg.moonrise.hawkeye.Test", "main", "After close", "After close", null)),
                "close() has begun");

        finishReport.countDown();
        reporter.join(5000);
        closer.join(10_000);

        assertFalse(closer.isAlive());
        Path file = folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt");
        assertEquals(new ReportResult.First(file, 2), late.get());
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertContainsInOrder(text, "#1 WARN", "#2 ERROR at 2026-11-25 14:03:06.000 +00:00", "Logged during shutdown");
        assertFalse(text.contains("After close"), text);
    }

    @Test
    public void theWriterIsADaemonThreadOfItsOwnThatEndsWithTheLog() throws Exception {
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        ErrorLog log = new ErrorLog(folder, Clock.fixed(Instant.ofEpochMilli(AT_14_03_05), ZoneOffset.UTC),
                () -> SERVER, report -> writerThread.set(Thread.currentThread()));
        log.start();
        log.report(problem(AT_14_03_05, "Could not write", null));
        log.flush().get(5, TimeUnit.SECONDS);

        Thread thread = writerThread.get();
        assertEquals("Hawkeye-ErrorLog", thread.getName());
        assertTrue(thread.isDaemon());
        log.close(Duration.ofSeconds(5));
        thread.join(5000);
        assertFalse(thread.isAlive(), "close() shuts the writer's executor down");
    }

    private @NotNull ErrorLog started(@NotNull Supplier<ServerSnapshot> server) {
        ErrorLog log = new ErrorLog(folder, Clock.fixed(Instant.ofEpochMilli(AT_14_03_05), ZoneOffset.UTC), server,
                written::add);
        log.start();
        return log;
    }

    /** The server facts, the first read waiting for {@code release}: holds the writer in its first batch. */
    private static @NotNull Supplier<ServerSnapshot> heldOnce(@NotNull CountDownLatch entered,
                                                              @NotNull CountDownLatch release) {
        AtomicBoolean held = new AtomicBoolean();
        return () -> {
            if (held.compareAndSet(false, true)) {
                entered.countDown();
                await(release);
            }
            return SERVER;
        };
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An exception whose first {@link #getCause()} (in {@link Problem#fingerprint()}) waits for {@code release}. */
    private static final class HeldCause extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch entered;
        private final transient CountDownLatch release;
        private final AtomicBoolean held = new AtomicBoolean();

        private HeldCause(@NotNull CountDownLatch entered, @NotNull CountDownLatch release) {
            super("held");
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Throwable getCause() {
            if (held.compareAndSet(false, true)) {
                entered.countDown();
                await(release);
            }
            return super.getCause();
        }
    }

    private @NotNull String close(@NotNull Path file) throws IOException {
        errorLog.close(Duration.ofSeconds(5));
        return Files.readString(file, StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    private static @NotNull Problem problem(long timeMillis, @NotNull String message, @Nullable Throwable thrown) {
        return new Problem(timeMillis, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.core.queue.event.EventWriter",
                "Hawkeye-Writer", "Could not write {} event(s) (attempt {})", message, thrown);
    }

    private static int count(@NotNull String text, @NotNull String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static void assertContainsInOrder(@NotNull String text, @NotNull String... parts) {
        int from = 0;
        for (String part : parts) {
            int at = text.indexOf(part, from);
            assertTrue(at >= 0, "missing after offset " + from + ": " + part + "\n--- text ---\n" + text);
            from = at + part.length();
        }
    }
}
