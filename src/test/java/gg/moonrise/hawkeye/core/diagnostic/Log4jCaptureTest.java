package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The filter on the real log4j-core the tests log through (SLF4J included), as on a Paper server. */
public class Log4jCaptureTest {

    private static final String WRITER = "gg.moonrise.hawkeye.core.queue.event.EventWriter";
    private static final String OTHER_PLUGIN = "OtherPlugin";
    private static final Pattern DETAILS = Pattern.compile("\\(details in (errors|crash)/((Error|Crash)"
            + "-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.txt)\\)$");

    @TempDir
    private Path folder;

    private ErrorLog errorLog;
    private ConsoleCapture capture;
    private ConsoleLines console;

    @BeforeEach
    public void setUp() {
        errorLog = new ErrorLog(folder, Clock.systemDefaultZone(), () -> new ServerSnapshot("1.21.1", "Paper",
                "Paper", "test", "21", "test", "26.4", List.of()), ReportListener.NONE);
        errorLog.start();
        capture = Log4jCapture.install(errorLog, "Hawkeye", OTHER_PLUGIN::equals, folder).orElseThrow();
        console = ConsoleLines.attach();
    }

    @AfterEach
    public void tearDown() {
        console.close();
        capture.close();
        errorLog.close(Duration.ofSeconds(5));
    }

    @Test
    public void anErrorIsShownOnceWithoutItsStackTraceAndRepeatsOnlyGoToTheFile() throws Exception {
        Logger logger = LoggerFactory.getLogger(WRITER);
        for (int attempt = 1; attempt <= 3; attempt++) {
            logger.error("The writer loop failed (attempt {}), retrying", attempt,
                    new SQLException("database is locked"));
        }

        List<LogEvent> lines = console.of(WRITER);
        assertEquals(1, lines.size(), "shown once: " + lines);
        LogEvent line = lines.getFirst();
        assertEquals(Level.ERROR, line.getLevel());
        assertNull(line.getThrown(), "no stack trace in the console");
        String text = line.getMessage().getFormattedMessage();
        assertTrue(text.startsWith("The writer loop failed (attempt 1), retrying: SQLException: database is locked "
                + "(details in errors/Error-"), text);
        String file = read(fileOf(text));
        assertTrue(file.contains("#1 ERROR at "), file);
        assertTrue(file.contains("The writer loop failed (attempt 1), retrying\njava.sql.SQLException: database is "
                + "locked\n\tat gg.moonrise.hawkeye.core.diagnostic.Log4jCaptureTest."), file);
        assertTrue(file.contains("#1 again (2 times): The writer loop failed (attempt 2), retrying: SQLException: "
                + "database is locked"), file);
        assertTrue(file.contains("#1 again (3 times): The writer loop failed (attempt 3)"), file);
    }

    @Test
    public void aRepeatedWarningIsShownOnce() throws Exception {
        Logger logger = LoggerFactory.getLogger("gg.moonrise.hawkeye.core.storage.shard.StorageManager");
        for (int attempt = 1; attempt <= 4; attempt++) {
            logger.warn("Could not connect to the database (attempt {}), retrying in {} s: {}", attempt, 2, "refused");
        }

        List<LogEvent> lines = console.of("gg.moonrise.hawkeye.core.storage.shard.StorageManager");
        assertEquals(1, lines.size(), "shown once: " + lines);
        assertEquals(Level.WARN, lines.getFirst().getLevel());
        String file = read(fileOf(lines.getFirst().getMessage().getFormattedMessage()));
        assertTrue(file.contains("#1 again (4 times): Could not connect to the database (attempt 4)"), file);
    }

    @Test
    public void thePluginLoggerIsHawkeyes() throws Exception {
        org.apache.logging.log4j.Logger plugin = LogManager.getLogger("Hawkeye");
        for (int i = 0; i < 2; i++) {
            plugin.error("Could not pass event BlockBreakEvent to Hawkeye v26.4",
                    new IllegalStateException("listener failed"));
        }

        List<LogEvent> lines = console.of("Hawkeye");
        assertEquals(1, lines.size(), "shown once: " + lines);
        assertTrue(lines.getFirst().getMessage().getFormattedMessage().startsWith("Could not pass event "
                + "BlockBreakEvent to Hawkeye v26.4: IllegalStateException: listener failed (details in errors/"));
    }

    @Test
    public void hikariWarningsAboutAHawkeyePoolAreHawkeyes() throws Exception {
        Logger hikari = LoggerFactory.getLogger("com.zaxxer.hikari.pool.PoolBase");
        for (int i = 0; i < 2; i++) {
            hikari.warn("{} - Pool is empty, failed to create/setup connection ({})", "Hawkeye-home", "id-" + i,
                    new SQLException("Connection refused"));
        }
        hikari.warn("{} - Pool is empty, failed to create/setup connection ({})", "OtherPlugin-pool", "id",
                new SQLException("Connection refused"));

        List<LogEvent> lines = console.of("com.zaxxer.hikari.pool.PoolBase");
        assertEquals(2, lines.size(), lines.toString());
        String ours = lines.getFirst().getMessage().getFormattedMessage();
        assertTrue(ours.startsWith("Hawkeye-home - Pool is empty, failed to create/setup connection (id-0): "
                + "SQLException: Connection refused (details in errors/Error-"), ours);
        assertNull(lines.getFirst().getThrown());
        assertTrue(read(fileOf(ours)).contains("#1 again (2 times): Hawkeye-home - Pool is empty"));
        assertTrue(lines.get(1).getMessage().getFormattedMessage().startsWith("OtherPlugin-pool"));
        assertNotNull(lines.get(1).getThrown(), "another plugin's pool is not Hawkeye's");
    }

    @Test
    public void otherPluginsAndQuietLevelsPassAsTheyAre() {
        Logger other = LoggerFactory.getLogger("com.example.OtherPlugin");
        other.error("Their failure", new IllegalStateException("theirs"));
        other.error("Their failure", new IllegalStateException("theirs"));
        LoggerFactory.getLogger(WRITER).info("Wrote 5 event(s)");

        List<LogEvent> lines = console.of("com.example.OtherPlugin");
        assertEquals(2, lines.size());
        assertNotNull(lines.getFirst().getThrown(), "their stack trace is kept");
        assertTrue(console.of(WRITER).isEmpty(), "INFO is below the test root level, and no error was filed");
        assertFalse(Files.exists(folder.resolve("errors")));
    }

    @Test
    public void whatTheStartupThreadLogsAtErrorIsACrash() throws Exception {
        Logger spring = LoggerFactory.getLogger("org.springframework.context.support.AbstractApplicationContext");
        errorLog.startupBegins();
        try {
            spring.error("Context initialization failed", new IllegalStateException("no bean"));
        } finally {
            errorLog.startupEnds();
        }
        spring.error("Someone else's context failed", new IllegalStateException("not ours"));

        List<LogEvent> lines = console.of(spring.getName());
        assertEquals(2, lines.size());
        String crash = lines.getFirst().getMessage().getFormattedMessage();
        assertTrue(crash.contains("(details in crash/Crash-"), crash);
        assertTrue(read(fileOf(crash)).startsWith("Hawkeye crash report"));
        assertEquals("Someone else's context failed", lines.get(1).getMessage().getFormattedMessage());
        assertNotNull(lines.get(1).getThrown(), "outside Hawkeye's startup a library's log is not Hawkeye's");
    }

    @Test
    public void anotherPluginsLogOnTheStartupThreadPassesAsItIs() throws Exception {
        org.apache.logging.log4j.Logger other = LogManager.getLogger(OTHER_PLUGIN);
        errorLog.startupBegins();
        try {
            // Hawkeye's ServicesManager registration runs other plugins' ServiceRegisterEvent listeners.
            other.warn("Their warning");
            other.error("Could not pass event ServiceRegisterEvent to OtherPlugin v1.0",
                    new IllegalStateException("their listener"));
            LogManager.getLogger("Hawkeye").error("Hawkeye's own", new IllegalStateException("ours"));
        } finally {
            errorLog.startupEnds();
        }

        List<LogEvent> theirs = console.of(OTHER_PLUGIN);
        assertEquals(2, theirs.size(), theirs.toString());
        assertEquals("Their warning", theirs.getFirst().getMessage().getFormattedMessage());
        assertEquals("Could not pass event ServiceRegisterEvent to OtherPlugin v1.0",
                theirs.get(1).getMessage().getFormattedMessage());
        assertNotNull(theirs.get(1).getThrown(), "their stack trace is kept");
        String ours = console.of("Hawkeye").getFirst().getMessage().getFormattedMessage();
        assertTrue(ours.contains("(details in crash/Crash-"), ours);
        assertFalse(read(fileOf(ours)).contains("ServiceRegisterEvent"), "not filed as Hawkeye's crash");
    }

    @Test
    public void theFilterFollowsTheRootLoggerWhenTheLoggingIsReconfigured() throws Exception {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration before = context.getConfiguration();
        console.close();
        // What a monitorInterval reload or another plugin's reconfigure does: a new configuration from the file.
        context.reconfigure();
        assertNotSame(before, context.getConfiguration());
        console = ConsoleLines.attach();

        Logger logger = LoggerFactory.getLogger(WRITER);
        logger.error("The writer loop failed", new SQLException("locked"));
        logger.error("The writer loop failed", new SQLException("locked"));

        List<LogEvent> lines = console.of(WRITER);
        assertEquals(1, lines.size(), "shown once: " + lines);
        assertNull(lines.getFirst().getThrown());
        assertTrue(read(fileOf(lines.getFirst().getMessage().getFormattedMessage()))
                .contains("#1 again (2 times): The writer loop failed"));

        capture.close();
        logger.error("After close", new SQLException("locked"));
        assertNotNull(console.of(WRITER).getLast().getThrown(), "removed from the new root logger");
    }

    @Test
    public void aWarningOfTheStartupThreadIsAnErrorNotACrash() throws Exception {
        errorLog.startupBegins();
        try {
            LoggerFactory.getLogger("gg.moonrise.hawkeye.command.registry.CommandAliasProcessor")
                    .warn("commands.yml: there is no subcommand '{}'; its aliases are ignored", "lookup2");
        } finally {
            errorLog.startupEnds();
        }

        String line = console.of("gg.moonrise.hawkeye.command.registry.CommandAliasProcessor").getFirst().getMessage()
                .getFormattedMessage();
        assertTrue(line.contains("(details in errors/Error-"), line);
    }

    @Test
    public void theCrashMarkerFilesACrash() throws Exception {
        LoggerFactory.getLogger("gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors")
                .error(LogMarkers.CRASH, "Uncaught exception on {}", "Hawkeye-Query-1", new OutOfMemoryError("test"));

        String line = console.of("gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors").getFirst().getMessage()
                .getFormattedMessage();
        assertTrue(line.startsWith("Uncaught exception on Hawkeye-Query-1: OutOfMemoryError: test (details in "
                + "crash/Crash-"), line);
    }

    @Test
    public void afterCloseEverythingIsLoggedAsBefore() throws IOException {
        capture.close();

        LoggerFactory.getLogger(WRITER).error("The writer loop failed", new SQLException("locked"));

        List<LogEvent> lines = console.of(WRITER);
        assertEquals(1, lines.size());
        assertEquals("The writer loop failed", lines.getFirst().getMessage().getFormattedMessage());
        assertNotNull(lines.getFirst().getThrown());
        try (Stream<Path> files = Files.exists(folder.resolve("errors")) ? Files.list(folder.resolve("errors"))
                : Stream.empty()) {
            assertEquals(0, files.count());
        }
    }

    private @NotNull Path fileOf(@NotNull String line) throws Exception {
        Matcher matcher = DETAILS.matcher(line);
        assertTrue(matcher.find(), "names its file: " + line);
        errorLog.flush().get(5, TimeUnit.SECONDS);
        return folder.resolve(matcher.group(1)).resolve(matcher.group(2));
    }

    private static @NotNull String read(@NotNull Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }
}
