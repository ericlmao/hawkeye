package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.core.service.dev.DevDataListener;
import gg.moonrise.hawkeye.core.service.dev.DevDataOutcome;
import gg.moonrise.hawkeye.core.service.dev.DevDataProgress;
import gg.moonrise.hawkeye.core.service.dev.DevDataRequest;
import gg.moonrise.hawkeye.core.service.dev.DevDataService;
import gg.moonrise.hawkeye.core.service.dev.DevDataTuning;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class DevCommandTest {

    private static final String DISABLED = "Dev mode is off. It is for developing Hawkeye, never for a real server: "
            + "set dev-mode.enabled in config.yml and run /he reload.";
    private static final String DONE = "Fake data done: ";

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        console = server.getConsoleSender();
        commands = RollbackTestSupport.commands();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void everyCommandOnlySaysDevModeIsOffWhileItIsOff() throws Exception {
        for (String command : List.of("hawkeye dev generate 100", "hawkeye dev status", "hawkeye dev cancel")) {
            commands.run(console, command);
            assertEquals(List.of(DISABLED), RollbackTestSupport.tickUntilReply(server, console, "Dev mode is off"),
                    command);
        }
        server.getScheduler().performTicks(5);
        assertEquals(List.of(), TestPlugins.drain(console));
        assertEquals(0, fake().size());
    }

    @Test
    public void theConsoleGeneratesFakeData() throws Exception {
        devMode();

        commands.run(console, "hawkeye dev generate 200");

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, DONE);
        assertEquals("Generating 200 fake event(s) over the last 30 day(s) as server-dev. Use /he dev status to "
                + "follow it.", replies.getFirst());
        String done = replies.getLast();
        assertTrue(done.startsWith("Fake data done: 200 event(s) by 8 fake player(s) written as server-dev in "), done);
        assertEquals(200, fake().size());
    }

    @Test
    public void theDayCountIsTheSecondArgument() {
        devMode();

        commands.run(console, "he dev generate 100 3");

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, DONE);
        assertTrue(replies.getFirst().contains(" over the last 3 day(s) "), replies.toString());
    }

    @Test
    public void countsAndDaysBelowOneAreRefused() throws Exception {
        devMode();

        commands.run(console, "hawkeye dev generate 0");
        assertEquals(List.of("The number of events must be at least 1."),
                RollbackTestSupport.tickUntilReply(server, console, "must be"));
        commands.run(console, "hawkeye dev generate 10 0");
        assertEquals(List.of("The number of days must be at least 1."),
                RollbackTestSupport.tickUntilReply(server, console, "must be"));
        assertEquals(0, fake().size());
    }

    @Test
    public void progressLinesComeBeforeTheDoneLine() {
        devMode();
        DevDataTuning.tune(16, 5);

        commands.run(console, "hawkeye dev generate 3000");

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, DONE);
        int done = replies.size() - 1;
        assertTrue(replies.get(done).startsWith(DONE), replies.toString());
        assertTrue(replies.subList(1, done).stream().anyMatch(line -> line.startsWith("Generating: ")),
                replies.toString());
        assertTrue(replies.subList(1, done).stream().allMatch(line -> line.startsWith("Generating: ")),
                replies.toString());
        server.getScheduler().performTicks(20);
        assertTrue(TestPlugins.drain(console).stream().noneMatch(line -> line.startsWith("Generating: ")),
                "no progress line after the done line");
    }

    @Test
    public void statusAndCancelWithoutARun() {
        devMode();

        commands.run(console, "hawkeye dev status");
        assertEquals(List.of("No fake data is being generated."),
                RollbackTestSupport.tickUntilReply(server, console, "No fake data"));
        commands.run(console, "hawkeye dev cancel");
        assertEquals(List.of("No fake data is being generated."),
                RollbackTestSupport.tickUntilReply(server, console, "No fake data"));
    }

    @Test
    public void statusAndCancelOfARunningGeneration() throws Exception {
        devMode();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<DevDataOutcome> running = holdRun(opened, hold);
        try {
            assertTrue(opened.await(20, TimeUnit.SECONDS));
            commands.run(console, "hawkeye dev status");
            String status = RollbackTestSupport.tickUntilReply(server, console, "Generating fake data as").getFirst();
            assertTrue(status.startsWith("Generating fake data as server-dev for "), status);
            assertTrue(status.endsWith(": 0/5000 event(s) (0%), 0 events/s, about ? left."), status);

            commands.run(console, "he dev cancel");
            assertEquals(List.of("Stopping after the current batch..."),
                    RollbackTestSupport.tickUntilReply(server, console, "Stopping"));
        } finally {
            hold.countDown();
        }
        DevDataOutcome.Cancelled cancelled = assertInstanceOf(DevDataOutcome.Cancelled.class,
                running.get(20, TimeUnit.SECONDS));
        assertEquals(0, cancelled.totals().written());
    }

    @Test
    public void aSecondGenerationWhileOneRunsIsRefused() throws Exception {
        devMode();
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<DevDataOutcome> running = holdRun(new CountDownLatch(1), hold);
        try {
            commands.run(console, "hawkeye dev generate 100");
            RollbackTestSupport.tickUntilReply(server, console, "Fake data is already being generated; see /he dev "
                    + "status.");
        } finally {
            hold.countDown();
        }
        running.get(20, TimeUnit.SECONDS);
    }

    @Test
    public void playersNeedThePermission() throws Exception {
        devMode();
        PlayerMock steve = server.addPlayer("Steve");
        TestPlugins.drain(steve);
        for (String command : List.of("he dev generate 100", "he dev status", "he dev cancel")) {
            try {
                commands.run(steve, command);
            } catch (CompletionException refused) {
                // Cloud refuses the command for lack of permission.
            }
        }
        server.getScheduler().performTicks(5);
        assertTrue(TestPlugins.drain(steve).isEmpty());
        assertEquals(0, fake().size());

        steve.addAttachment(plugin, Permissions.DEV, true);
        commands.run(steve, "he dev status");
        RollbackTestSupport.tickUntilReply(server, steve, "No fake data is being generated.");
    }

    @Test
    public void aPlayersOwnWorldComesFirst() throws Exception {
        devMode();
        World world = server.addSimpleWorld("world");
        PlayerMock steve = server.addPlayer("Steve");
        steve.setLocation(new Location(world, 5_000, 70, -3_000));
        steve.addAttachment(plugin, Permissions.DEV, true);
        TestPlugins.drain(steve);

        commands.run(steve, "he dev generate 300 2");

        RollbackTestSupport.tickUntilReply(server, steve, DONE);
        List<StoredEvent> stored = fake();
        assertEquals(300, stored.size());
        Set<String> names = server.getWorlds().stream().map(World::getName).collect(Collectors.toSet());
        assertTrue(stored.stream().allMatch(event -> names.contains(event.world())), stored.toString());
        assertTrue(stored.stream().filter(event -> event.world().equals("world"))
                .allMatch(event -> Math.abs(event.x() - 5_000) <= 8_192 && Math.abs(event.z() + 3_000) <= 8_192));
    }

    private void devMode() {
        TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: true\n");
        TestPlugins.reload();
    }

    private static @NotNull CompletableFuture<DevDataOutcome> holdRun(@NotNull CountDownLatch opened,
            @NotNull CountDownLatch hold) {
        return TestPlugins.bean(DevDataService.class).start(new DevDataRequest(5_000, 2, List.of(), "test"),
                new DevDataListener() {
                    @Override
                    public void started(@NotNull DevDataProgress progress) {
                        opened.countDown();
                        await(hold);
                    }
                });
    }

    /**
     * Every fake event: what the dev nodes hold. What the plugin records itself (a test player's join and
     * inventory snapshot) is left out, since the writer may or may not have flushed it yet.
     */
    private static @NotNull List<StoredEvent> fake() throws SQLException {
        return TestPlugins.bean(EventStore.class).find(TestPlugins.awaitStorage().home(),
                LookupQuery.at(new Area.Everywhere()), new ShardScope.All(), 0, 100_000, false).stream()
                .filter(event -> event.server().contains("-dev"))
                .toList();
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
