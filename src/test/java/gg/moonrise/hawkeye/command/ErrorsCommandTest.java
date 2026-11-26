package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.diagnostic.ReportFiles;
import gg.moonrise.hawkeye.core.queue.event.EventWriter;
import gg.moonrise.hawkeye.core.service.diagnostic.ErrorReportService;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.slf4j.LoggerFactory;

public class ErrorsCommandTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSender console;

    @BeforeEach
    public void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        commands = TestPlugins.commands();
        console = server.getConsoleSender();
        // MockBukkit cannot build Cloud's command manager, which Hawkeye logs as a startup error: start clean.
        plugin.diagnostics().orElseThrow().errorLog().flush().get(5, TimeUnit.SECONDS);
        TestPlugins.bean(ErrorReportService.class).markReviewed().get(5, TimeUnit.SECONDS);
        TestPlugins.drain(console);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void nothingWaits() {
        commands.run(console, "hawkeye errors");

        List<String> replies = AwaitReplies.reply(server, console);
        assertEquals(List.of("No error or crash report waits to be reviewed."), replies);
    }

    @Test
    public void waitingFilesAreListedAndMarkedReviewed() throws Exception {
        LoggerFactory.getLogger(EventWriter.class).error("The writer loop failed (attempt {}), retrying", 1,
                new SQLException("disk I/O error"));
        plugin.diagnostics().orElseThrow().errorLog().flush().get(5, TimeUnit.SECONDS);

        commands.run(console, "hawkeye errors");
        List<String> list = AwaitReplies.until(server, console,
                received -> received.stream().anyMatch(line -> line.contains("/hawkeye errors reviewed")));

        assertEquals("----- Hawkeye reports waiting for review: 1 -----", list.getFirst());
        assertTrue(list.get(1).startsWith("error errors/Error-"), list.get(1));
        assertTrue(list.get(1).contains("(1 problem(s), "), list.get(1));
        assertTrue(list.get(1).endsWith("The writer loop failed (attempt 1), retrying"), list.get(1));

        commands.run(console, "hawkeye errors reviewed");
        List<String> reviewed = AwaitReplies.reply(server, console);
        assertEquals(List.of("Marked 1 report(s) as reviewed: they are in plugins/Hawkeye/errors/reviewed and "
                + "crash/reviewed now."), reviewed);
        Path errors = plugin.getDataPath().resolve("errors");
        try (var files = Files.list(errors.resolve(ReportFiles.REVIEWED))) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith("Error-")));
        }
        try (var files = Files.list(errors)) {
            assertFalse(files.anyMatch(Files::isRegularFile), "nothing left waiting");
        }

        commands.run(console, "hawkeye errors");
        assertEquals(List.of("No error or crash report waits to be reviewed."), AwaitReplies.reply(server, console));
    }
}
