package gg.moonrise.hawkeye.core.service.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.diagnostic.Diagnostics;
import gg.moonrise.hawkeye.core.diagnostic.ReportFiles;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class ErrorReportServiceTest {

    /** {@code HawkeyeExecutors}' query threads. */
    private static final int QUERY_THREADS = 4;

    private TestHawkeyePlugin plugin;
    private Diagnostics diagnostics;
    private ErrorReportService reports;

    @BeforeEach
    public void setUp() throws Exception {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        diagnostics = plugin.diagnostics().orElseThrow();
        reports = TestPlugins.bean(ErrorReportService.class);
        // MockBukkit cannot build Cloud's command manager, which Hawkeye logs as a startup error: start clean.
        diagnostics.errorLog().flush().get(5, TimeUnit.SECONDS);
        reports.markReviewed().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theWaitingFilesAreCountedFromTheFolders() throws Exception {
        Files.writeString(Files.createDirectories(plugin.getDataPath().resolve("crash"))
                .resolve("Crash-2026-11-25-14-03.txt"), "not read");
        Path errors = Files.createDirectories(plugin.getDataPath().resolve("errors"));
        Files.writeString(errors.resolve("Error-2026-11-25-14-03.txt"), "not read");
        Files.writeString(errors.resolve("Error-2026-11-25-14-05.txt"), "not read");

        assertEquals(new PendingCount(1, 2), reports.pendingCount().get(5, TimeUnit.SECONDS));
    }

    @Test
    public void withTheErrorLogClosedFilesAreMovedOffTheCallingThread() throws Exception {
        diagnostics.errorLog().close(Duration.ofSeconds(5));
        Path errors = Files.createDirectories(plugin.getDataPath().resolve("errors"));
        Path waiting = Files.writeString(errors.resolve("Error-2026-11-25-14-03.txt"), "waiting");
        // Every query thread waits, so work handed to the pool cannot start before the latch opens.
        ExecutorService query = TestPlugins.bean(HawkeyeExecutors.class).query();
        CountDownLatch busy = new CountDownLatch(QUERY_THREADS);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < QUERY_THREADS; i++) {
            query.execute(() -> {
                busy.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(busy.await(5, TimeUnit.SECONDS));

        CompletableFuture<Integer> moved;
        try {
            moved = reports.markReviewed();
            assertFalse(moved.isDone(), "the caller's thread moved the files");
            assertTrue(Files.exists(waiting));
        } finally {
            release.countDown();
        }

        assertEquals(1, moved.get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(waiting));
        assertTrue(Files.isRegularFile(errors.resolve(ReportFiles.REVIEWED).resolve("Error-2026-11-25-14-03.txt")));
    }
}
