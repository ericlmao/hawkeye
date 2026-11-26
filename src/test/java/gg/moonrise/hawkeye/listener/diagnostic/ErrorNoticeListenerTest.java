package gg.moonrise.hawkeye.listener.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.queue.event.EventWriter;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.diagnostic.ErrorReportService;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.LoggerFactory;

public class ErrorNoticeListenerTest {

    private static final String NOTICE = "wait to be reviewed: /hawkeye errors";

    private ServerMock server;
    private TestHawkeyePlugin plugin;

    @BeforeEach
    public void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        // MockBukkit cannot build Cloud's command manager, which Hawkeye logs as a startup error: start clean.
        flush();
        TestPlugins.bean(ErrorReportService.class).markReviewed().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anOpIsToldHowManyFilesWait() throws Exception {
        LoggerFactory.getLogger(EventWriter.class).error("The writer loop failed", new SQLException("locked"));
        LoggerFactory.getLogger(HawkeyeExecutors.class).error(LogMarkers.CRASH, "Uncaught exception on {}",
                "Hawkeye-Query-1", new IllegalStateException("boom"));
        flush();

        PlayerMock op = join("Admin", true);

        List<String> received = AwaitReplies.until(server, op, lines -> lines.stream().anyMatch(this::isNotice));
        assertEquals(List.of("Hawkeye » 1 crash report(s) and 1 error report(s) " + NOTICE), received);
    }

    @Test
    public void onlyTheKindsThatWaitAreNamed() throws Exception {
        LoggerFactory.getLogger(EventWriter.class).warn("Could not write {} event(s)", 5);
        flush();

        PlayerMock op = join("Admin", true);

        List<String> received = AwaitReplies.until(server, op, lines -> lines.stream().anyMatch(this::isNotice));
        assertEquals(List.of("Hawkeye » 1 error report(s) " + NOTICE), received);
    }

    @Test
    public void aPlayerWithoutThePermissionIsNotTold() throws Exception {
        LoggerFactory.getLogger(EventWriter.class).error("The writer loop failed", new SQLException("locked"));
        flush();

        PlayerMock player = join("Builder", false);

        assertTrue(AwaitReplies.during(server, player, 1500).stream().noneMatch(this::isNotice));
    }

    @Test
    public void nothingIsToldWhenNothingWaits() {
        PlayerMock op = join("Admin", true);

        assertTrue(AwaitReplies.during(server, op, 1500).stream().noneMatch(this::isNotice));
    }

    @Test
    public void theNoticeCanBeSwitchedOff() throws Exception {
        LoggerFactory.getLogger(EventWriter.class).error("The writer loop failed", new SQLException("locked"));
        flush();
        TestPlugins.writeConfig(plugin, "error-reports:\n  join-notice: false\n");
        TestPlugins.reload();

        PlayerMock op = join("Admin", true);

        assertTrue(AwaitReplies.during(server, op, 1500).stream().noneMatch(this::isNotice));
    }

    private boolean isNotice(@NotNull String line) {
        return line.contains(NOTICE);
    }

    /** MockBukkit takes an operator only once the player is on the server, so the join is fired again then. */
    private @NotNull PlayerMock join(@NotNull String name, boolean op) {
        PlayerMock player = server.addPlayer(name);
        player.setOp(op);
        TestPlugins.drain(player);
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, Component.empty()));
        return player;
    }

    private void flush() throws Exception {
        plugin.diagnostics().orElseThrow().errorLog().flush().get(5, TimeUnit.SECONDS);
    }
}
