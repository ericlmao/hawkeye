package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.entity.diagnostic.PendingReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReportFilesTest {

    private static final long AT_14_03 = Instant.parse("2026-11-25T14:03:05Z").toEpochMilli();

    @TempDir
    private Path folder;

    @Test
    public void nothingWaitsWithoutFolders() throws IOException {
        assertTrue(new ReportFiles(folder).pending().isEmpty());
        assertTrue(new ReportFiles(folder).markReviewed().isEmpty());
    }

    @Test
    public void filesTheErrorLogWroteWaitWithTheirFirstProblem() throws IOException {
        write(new Problem(AT_14_03, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.core.storage.shard.StorageManager",
                        "Hawkeye-Maintenance-1", "Could not connect (attempt {})", "Could not connect (attempt 1)",
                        null),
                new Problem(AT_14_03 + 1000, ProblemKind.ERROR, "ERROR",
                        "gg.moonrise.hawkeye.core.queue.event.EventWriter",
                        "Hawkeye-Writer", "The writer loop failed", "The writer loop failed",
                        new SQLException("locked")),
                new Problem(AT_14_03 + 120_000, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.Other", "main",
                        "Later", "Later", null),
                new Problem(AT_14_03, ProblemKind.CRASH, "ERROR", "gg.moonrise.hawkeye.HawkeyePlugin", "Server thread",
                        "Hawkeye could not {}", "Hawkeye could not load and is disabled", null));
        Files.writeString(folder.resolve("errors").resolve("notes.txt"), "not a report");

        List<PendingReport> pending = new ReportFiles(folder).pending();

        assertEquals(List.of("Crash-2026-11-25-14-03.txt", "Error-2026-11-25-14-03.txt", "Error-2026-11-25-14-05.txt"),
                pending.stream().map(PendingReport::fileName).toList(), "crashes first, then oldest first");
        PendingReport crash = pending.get(0);
        assertEquals(ProblemKind.CRASH, crash.kind());
        assertEquals(1, crash.problems());
        assertEquals("Hawkeye could not load and is disabled", crash.firstProblem());
        PendingReport errors = pending.get(1);
        assertEquals(ProblemKind.ERROR, errors.kind());
        assertEquals(2, errors.problems());
        assertEquals("Could not connect (attempt 1)", errors.firstProblem());
        assertEquals(Files.size(folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt")), errors.sizeBytes());
        assertTrue(errors.modifiedMillis() > 0);
    }

    @Test
    public void waitingCountsTheFilesOfEachKindWithoutReadingThem() throws IOException {
        assertEquals(new PendingCount(0, 0), new ReportFiles(folder).waiting());
        assertTrue(new ReportFiles(folder).waiting().isEmpty());

        write(new Problem(AT_14_03, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.Test", "main", "A", "A", null),
                new Problem(AT_14_03 + 120_000, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.Test", "main", "B",
                        "B", null),
                new Problem(AT_14_03, ProblemKind.CRASH, "ERROR", "gg.moonrise.hawkeye.Test", "main", "C", "C", null));
        Files.writeString(folder.resolve("errors").resolve("notes.txt"), "not a report");
        Files.createDirectories(folder.resolve("errors").resolve(ReportFiles.REVIEWED));
        Files.writeString(folder.resolve("errors").resolve(ReportFiles.REVIEWED).resolve("Error-2026-11-24-10-00.txt"),
                "reviewed before");

        PendingCount waiting = new ReportFiles(folder).waiting();

        assertEquals(new PendingCount(1, 2), waiting);
        assertFalse(waiting.isEmpty());
        assertEquals(waiting.errors(), (int) new ReportFiles(folder).pending().stream()
                .filter(report -> report.kind() == ProblemKind.ERROR).count());
    }

    @Test
    public void reviewedFilesMoveToTheReviewedFoldersWithoutOverwriting() throws IOException {
        write(new Problem(AT_14_03, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.Test", "main", "A", "A", null),
                new Problem(AT_14_03, ProblemKind.CRASH, "ERROR", "gg.moonrise.hawkeye.Test", "main", "B", "B", null));
        Path reviewed = folder.resolve("errors").resolve(ReportFiles.REVIEWED);
        Files.createDirectories(reviewed);
        Files.writeString(reviewed.resolve("Error-2026-11-25-14-03.txt"), "reviewed before");

        List<ReportFiles.Moved> moved = new ReportFiles(folder).markReviewed();

        assertEquals(2, moved.size());
        assertTrue(moved.contains(new ReportFiles.Moved(folder.resolve("errors").resolve("Error-2026-11-25-14-03.txt"),
                reviewed.resolve("Error-2026-11-25-14-03-2.txt"))), moved.toString());
        assertTrue(new ReportFiles(folder).pending().isEmpty());
        assertEquals("reviewed before", Files.readString(reviewed.resolve("Error-2026-11-25-14-03.txt")));
        assertTrue(Files.readString(reviewed.resolve("Error-2026-11-25-14-03-2.txt"))
                .startsWith("Hawkeye error report"));
        assertTrue(Files.isRegularFile(folder.resolve("crash").resolve(ReportFiles.REVIEWED)
                .resolve("Crash-2026-11-25-14-03.txt")));
        assertFalse(Files.exists(folder.resolve("crash").resolve("Crash-2026-11-25-14-03.txt")));
    }

    @Test
    public void aFolderWithNothingWaitingGetsNoReviewedFolder() throws IOException {
        write(new Problem(AT_14_03, ProblemKind.ERROR, "WARN", "gg.moonrise.hawkeye.Test", "main", "A", "A", null));
        Files.createDirectories(folder.resolve("crash"));

        assertEquals(1, new ReportFiles(folder).markReviewed().size());

        assertTrue(Files.isDirectory(folder.resolve("errors").resolve(ReportFiles.REVIEWED)));
        assertFalse(Files.exists(folder.resolve("crash").resolve(ReportFiles.REVIEWED)));
    }

    private void write(@NotNull Problem... problems) {
        ErrorLog errorLog = new ErrorLog(folder, Clock.fixed(Instant.ofEpochMilli(AT_14_03), ZoneOffset.UTC),
                () -> new ServerSnapshot("1.21.1", "Paper", "Paper", "test", "21", "test", "26.4", List.of()),
                ReportListener.NONE);
        errorLog.start();
        for (Problem problem : problems) {
            errorLog.report(problem);
        }
        errorLog.close(Duration.ofSeconds(5));
    }
}
