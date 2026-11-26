package gg.moonrise.hawkeye;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gg.moonrise.hawkeye.api.HawkeyeApi;
import gg.moonrise.hawkeye.core.diagnostic.ConsoleLines;
import gg.moonrise.hawkeye.core.diagnostic.Diagnostics;
import gg.moonrise.hawkeye.core.queue.event.EventWriter;
import gg.moonrise.hawkeye.support.FailingHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.moss.paper.MossPaper;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The error and crash files of the loaded plugin, on MockBukkit and the tests' real log4j. */
public class HawkeyePluginErrorLogTest {

    private static final Pattern DETAILS = Pattern.compile("\\(details in (.+\\.txt)\\)$");

    private ServerMock server;
    private ConsoleLines console;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        console = ConsoleLines.attach();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
        console.close();
    }

    @Test
    public void aContextThatFailsToStartIsACrashFileAndOneConsoleLine() throws IOException {
        FailingHawkeyePlugin plugin = MockBukkit.load(FailingHawkeyePlugin.class);

        assertFalse(plugin.isEnabled(), "the plugin disables itself");
        assertTrue(plugin.diagnostics().isEmpty(), "the error log closed with the plugin");
        List<LogEvent> lines = console.of(HawkeyePlugin.class.getName());
        assertEquals(1, lines.size(), "one console line: " + lines);
        assertNull(lines.getFirst().getThrown(), "without the stack trace");
        String line = lines.getFirst().getMessage().getFormattedMessage();
        assertTrue(line.startsWith("Hawkeye could not load and is disabled: IllegalArgumentException: storage.type: "
                + "'oracle' is unknown (details in "), line);

        List<Path> crashes = files(plugin.getDataPath().resolve("crash"));
        assertEquals(1, crashes.size(), crashes.toString());
        Path crash = crashes.getFirst();
        assertTrue(crash.getFileName().toString().matches("Crash-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.txt"),
                crash.toString());
        assertTrue(line.endsWith(crash.getFileName() + ")"), line);
        String text = read(crash);
        assertTrue(text.startsWith("Hawkeye crash report, started "), text);
        assertContainsInOrder(text, "  Minecraft version: " + server.getMinecraftVersion(),
                "  Server type: unknown", "  Server: " + server.getName() + " " + server.getVersion(),
                "  Java: " + Runtime.version(), "  Hawkeye: " + plugin.getPluginMeta().getVersion(), "Plugins (1)",
                "  " + plugin.getName() + " " + plugin.getPluginMeta().getVersion(),
                "#1 ERROR at ", "Logger: " + HawkeyePlugin.class.getName(), "Hawkeye could not load and is disabled",
                "java.lang.IllegalStateException: " + FailingHawkeyePlugin.FAILURE,
                "Caused by: java.lang.IllegalArgumentException: storage.type: 'oracle' is unknown");
        assertFalse(Files.exists(plugin.getDataPath().resolve("errors")), "a startup failure is no error file");
    }

    @Test
    public void aRepeatedErrorIsShownOnceAndItsRepeatsAreWrittenToItsFile() throws Exception {
        TestHawkeyePlugin plugin = TestPlugins.load();
        assertTrue(plugin.isEnabled());
        Logger writer = LoggerFactory.getLogger(EventWriter.class);

        for (int attempt = 1; attempt <= 3; attempt++) {
            writer.error("The writer loop failed (attempt {}), retrying", attempt, new SQLException("disk I/O error"));
        }
        plugin.diagnostics().orElseThrow().errorLog().flush().get(5, TimeUnit.SECONDS);

        List<LogEvent> lines = console.of(EventWriter.class.getName());
        assertEquals(1, lines.size(), "one console line: " + lines);
        Matcher details = DETAILS.matcher(lines.getFirst().getMessage().getFormattedMessage());
        assertTrue(details.find(), lines.getFirst().getMessage().getFormattedMessage());
        Path file = Path.of(details.group(1));
        assertEquals(plugin.getDataPath().resolve("errors").toAbsolutePath().normalize(),
                file.toAbsolutePath().normalize().getParent());
        String text = read(file);
        assertTrue(text.startsWith("Hawkeye error report, started "), text);
        assertContainsInOrder(text, "  Minecraft version: " + server.getMinecraftVersion(), "Plugins (",
                "ERROR at ", "The writer loop failed (attempt 1), retrying", "java.sql.SQLException: disk I/O error",
                "again (2 times): The writer loop failed (attempt 2), retrying: SQLException: disk I/O error",
                "again (3 times): The writer loop failed (attempt 3), retrying");
    }

    @Test
    public void disablingClosesTheErrorLogAndRemovesItsFilter() {
        TestHawkeyePlugin plugin = TestPlugins.load();

        server.getPluginManager().disablePlugin(plugin);
        LoggerFactory.getLogger(EventWriter.class).error("After the plugin stopped", new SQLException("closed"));

        assertTrue(plugin.diagnostics().isEmpty());
        assertNull(MossPaper.CONTEXT);
        List<LogEvent> lines = console.of(EventWriter.class.getName());
        assertEquals(1, lines.size());
        assertEquals("After the plugin stopped", lines.getFirst().getMessage().getFormattedMessage());
        assertTrue(lines.getFirst().getThrown() instanceof SQLException, "logged as it is");
    }

    @Test
    public void closingTheContextLeavesTheErrorLogOpenForWhatStopsAfterIt() throws Exception {
        TestHawkeyePlugin plugin = TestPlugins.load();
        Diagnostics diagnostics = plugin.diagnostics().orElseThrow();
        assertSame(diagnostics, TestPlugins.bean(Diagnostics.class), "registered in the context");

        // What MossPaper.onDisable does before HawkeyePlugin's finally closes the error log.
        plugin.disableComponents();
        MossPaper.CONTEXT.close();
        LoggerFactory.getLogger(EventWriter.class).error("Stopping after the context", new SQLException("closed"));
        diagnostics.errorLog().flush().get(5, TimeUnit.SECONDS);

        List<LogEvent> lines = console.of(EventWriter.class.getName());
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.getFirst().getMessage().getFormattedMessage();
        assertTrue(DETAILS.matcher(line).find(), "still filed, not printed as it is: " + line);
        assertNull(lines.getFirst().getThrown());
    }

    @Test
    public void theWebhookGetsTheFirstTimeOfEachProblemWithItsFile() throws IOException {
        HttpServer relay = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        relay.createContext("/api/webhooks/1/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        relay.start();
        try {
            File data = server.getPluginManager().createTemporaryDirectory("TestHawkeyePlugin-0.0.0");
            Files.writeString(data.toPath().resolve("config.yml"), "error-reports:\n  webhook-url: 'http://127.0.0.1:"
                    + relay.getAddress().getPort() + "/api/webhooks/1/token'\n");
            TestHawkeyePlugin plugin = TestPlugins.load();
            Logger writer = LoggerFactory.getLogger(EventWriter.class);
            for (int attempt = 1; attempt <= 3; attempt++) {
                writer.error("The writer loop failed (attempt {}), retrying", attempt,
                        new SQLException("disk I/O error"));
            }

            server.getPluginManager().disablePlugin(plugin);

            assertTrue(bodies.stream().anyMatch(body -> body.contains("\"title\":\"Crash on server\"")
                    && body.contains("The commands could not be registered")), "MockBukkit's startup error: " + bodies);
            // The attached file holds every problem of its minute; the embed names the one reported.
            List<String> writerReports = bodies.stream()
                    .filter(body -> body.contains("\"description\":\"```\\nThe writer loop failed")).toList();
            assertEquals(1, writerReports.size(), "repeats are not sent: " + writerReports);
            String report = writerReports.getFirst();
            assertTrue(report.contains("\"title\":\"Error on server\""), report);
            assertTrue(report.contains("The writer loop failed (attempt 1), retrying: SQLException: disk I/O error"),
                    report);
            assertTrue(report.contains("name=\"files[0]\"; filename=\"Error-"), report);
            assertTrue(report.contains("Hawkeye error report, started "), "the file is attached: " + report);
        } finally {
            relay.stop(0);
        }
    }

    @Test
    public void anotherPluginsListenerLoggingDuringHawkeyesStartupIsLeftAlone() throws Exception {
        TestHawkeyePlugin plugin = TestPlugins.load();
        PluginMock other = MockBukkit.createMockPlugin("OtherPlugin");
        server.getPluginManager().registerEvents(new RegistrationListener(other), other);
        RegisteredServiceProvider<HawkeyeApi> api = server.getServicesManager().getRegistration(HawkeyeApi.class);
        assertNotNull(api, "Hawkeye's enable registered its API");
        Diagnostics diagnostics = plugin.diagnostics().orElseThrow();

        // Paper fires this from Hawkeye's enable, on the thread running it; MockBukkit's services manager does not.
        diagnostics.startupBegins();
        try {
            server.getPluginManager().callEvent(new ServiceRegisterEvent(api));
        } finally {
            diagnostics.startupEnds();
        }
        diagnostics.errorLog().flush().get(5, TimeUnit.SECONDS);

        List<LogEvent> theirs = console.of(other.getName());
        assertEquals(1, theirs.size(), theirs.toString());
        assertEquals("Could not hook into HawkeyeApi", theirs.getFirst().getMessage().getFormattedMessage());
        assertNotNull(theirs.getFirst().getThrown(), "logged as it is");
        for (String folder : List.of("crash", "errors")) {
            for (Path file : files(plugin.getDataPath().resolve(folder))) {
                assertFalse(read(file).contains("Could not hook into"), "not filed by Hawkeye: " + file);
            }
        }
    }

    /**
     * Another plugin's listener that fails to use a service registered while Hawkeye enables. It logs to log4j under
     * its plugin's name, where Paper sends a plugin's {@code getLogger()} lines; the tests have no JUL bridge, so a
     * {@code getLogger()} line goes to JUL's own console handler and never reaches the error log's filter.
     */
    public static final class RegistrationListener implements Listener {

        private final @NotNull Plugin plugin;

        private RegistrationListener(@NotNull Plugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onRegister(@NotNull ServiceRegisterEvent event) {
            LogManager.getLogger(plugin.getName()).error("Could not hook into {}",
                    event.getProvider().getService().getSimpleName(), new IllegalStateException("their listener"));
        }
    }

    private static @NotNull List<Path> files(@NotNull Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.sorted().toList();
        }
    }

    private static @NotNull String read(@NotNull Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
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
