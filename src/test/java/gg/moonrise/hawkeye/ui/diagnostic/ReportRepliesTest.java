package gg.moonrise.hawkeye.ui.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.diagnostic.ConsoleLines;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ReportRepliesTest {

    private ServerMock server;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aJoinNoticeThatCannotBeCountedIsLoggedAndNotSent() {
        PlayerMock player = server.addPlayer("Admin");
        TestPlugins.drain(player);
        try (ConsoleLines console = ConsoleLines.attach()) {
            TestPlugins.bean(ReportReplies.class).joinNotice(player,
                    CompletableFuture.failedFuture(new IOException("the errors folder cannot be listed")));

            List<LogEvent> lines = console.of(ReportReplies.class.getName());
            assertEquals(1, lines.size(), lines.toString());
            assertEquals(Level.WARN, lines.getFirst().getLevel());
            String line = lines.getFirst().getMessage().getFormattedMessage();
            assertTrue(line.startsWith("Could not tell Admin on join about the error and crash files waiting for "
                    + "review: IOException: the errors folder cannot be listed"), line);
        }
        assertTrue(AwaitReplies.during(server, player, 500).isEmpty());
    }
}
