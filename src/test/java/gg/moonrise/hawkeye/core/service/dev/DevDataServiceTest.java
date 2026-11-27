package gg.moonrise.hawkeye.core.service.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Fake data end to end: generated into the plugin's own SQLite storage, on MockBukkit. */
public class DevDataServiceTest {

    private static final String NODE = "server-dev";
    private static final long DAY_MILLIS = 86_400_000L;

    private TestHawkeyePlugin plugin;
    private DevDataService service;
    private StorageState.Ready ready;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        ready = TestPlugins.awaitStorage();
        service = TestPlugins.bean(DevDataService.class);
        service.tune(64, DevDataService.PROGRESS_INTERVAL.toMillis());
        devMode(true);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void everyAskedForEventIsWrittenUnderItsOwnNode() throws Exception {
        AtomicInteger started = new AtomicInteger();
        DevDataOutcome outcome = run(request(500, 30), new DevDataListener() {
            @Override
            public void started(@NotNull DevDataProgress progress) {
                assertEquals(0, progress.written());
                assertEquals(NODE, progress.node());
                started.incrementAndGet();
            }
        });

        DevDataOutcome.Finished finished = assertInstanceOf(DevDataOutcome.Finished.class, outcome);
        assertEquals(500, finished.totals().written());
        assertEquals(500, finished.totals().count());
        assertEquals(100, finished.totals().percent());
        assertEquals(8, finished.totals().players());
        assertEquals(1, started.get());
        List<StoredEvent> stored = of(NODE);
        assertEquals(500, stored.size());
        assertEquals(500, all().size(), "nothing else was written");
        assertEquals(EnumSet.allOf(Action.class), stored.stream().map(StoredEvent::action)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Action.class))));
        assertEquals(500, stored.stream().map(StoredEvent::id).distinct().count());
        assertFalse(service.running());
    }

    @Test
    public void aSecondRunTakesTheNextNode() throws Exception {
        assertInstanceOf(DevDataOutcome.Finished.class, run(request(100, 2), DevDataListener.NONE));
        DevDataOutcome.Finished second = assertInstanceOf(DevDataOutcome.Finished.class,
                run(request(100, 2), DevDataListener.NONE));

        assertEquals(NODE + "-2", second.totals().node());
        assertEquals(100, of(NODE).size());
        assertEquals(100, of(NODE + "-2").size());
        assertEquals(200, all().stream().map(StoredEvent::id).distinct().count());
    }

    @Test
    public void nothingIsWrittenWhileDevModeIsOff() throws Exception {
        devMode(false);
        assertFalse(service.enabled());
        int servers = servers().size();

        assertInstanceOf(DevDataOutcome.Disabled.class, run(request(100, 2), DevDataListener.NONE));

        assertEquals(0, all().size());
        assertEquals(servers, servers().size(), "no node registered");
    }

    @Test
    public void aSecondRunWhileOneGoesIsRefused() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<DevDataOutcome> running = service.start(request(100, 2), holding(new CountDownLatch(1),
                hold));
        try {
            assertTrue(service.running());
            assertInstanceOf(DevDataOutcome.AlreadyRunning.class, run(request(100, 2), DevDataListener.NONE));
        } finally {
            hold.countDown();
        }
        assertInstanceOf(DevDataOutcome.Finished.class, running.get(30, TimeUnit.SECONDS));
    }

    @Test
    public void aCancelledRunKeepsWhatItWrote() throws Exception {
        service.tune(16, 5);
        DevDataOutcome outcome = run(request(200_000, 2), new DevDataListener() {
            @Override
            public void progress(@NotNull DevDataProgress progress) {
                if (progress.written() > 0) {
                    service.cancel();
                }
            }
        });

        DevDataOutcome.Cancelled cancelled = assertInstanceOf(DevDataOutcome.Cancelled.class, outcome);
        assertTrue(cancelled.totals().written() > 0 && cancelled.totals().written() < 200_000,
                cancelled.totals().toString());
        assertEquals(cancelled.totals().written(), of(NODE).size());
        assertFalse(service.cancel(), "nothing runs any more");
    }

    @Test
    public void turningDevModeOffStopsARun() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<DevDataOutcome> running = service.start(request(5_000, 2), holding(opened, hold));
        try {
            assertTrue(opened.await(20, TimeUnit.SECONDS));
            assertEquals(NODE, service.progress().orElseThrow().node());
            devMode(false);
        } finally {
            hold.countDown();
        }
        DevDataOutcome.Cancelled cancelled = assertInstanceOf(DevDataOutcome.Cancelled.class,
                running.get(30, TimeUnit.SECONDS));
        assertEquals(0, cancelled.totals().written());
        assertEquals(0, of(NODE).size());
    }

    @Test
    public void progressArrivesEveryIntervalAndNeverAfterTheOutcome() throws Exception {
        service.tune(16, 5);
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch heard = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        List<Long> written = new CopyOnWriteArrayList<>();
        AtomicReference<CompletableFuture<DevDataOutcome>> future = new AtomicReference<>();
        List<Boolean> afterOutcome = new CopyOnWriteArrayList<>();
        CompletableFuture<DevDataOutcome> running = service.start(request(3_000, 2), new DevDataListener() {
            @Override
            public void started(@NotNull DevDataProgress progress) {
                opened.countDown();
                await(hold);
            }

            @Override
            public void progress(@NotNull DevDataProgress progress) {
                CompletableFuture<DevDataOutcome> outcome = future.get();
                afterOutcome.add(outcome != null && outcome.isDone());
                written.add(progress.written());
                heard.countDown();
            }
        });
        future.set(running);
        try {
            assertTrue(opened.await(20, TimeUnit.SECONDS));
            assertTrue(heard.await(20, TimeUnit.SECONDS), "a progress line while the run is held");
        } finally {
            hold.countDown();
        }
        assertInstanceOf(DevDataOutcome.Finished.class, running.get(30, TimeUnit.SECONDS));
        int calls = written.size();
        Thread.sleep(50);

        assertEquals(calls, written.size(), "no progress after the outcome");
        assertFalse(afterOutcome.contains(true), "every progress call came before the outcome");
        for (int i = 1; i < written.size(); i++) {
            assertTrue(written.get(i) >= written.get(i - 1), written.toString());
        }
    }

    @Test
    public void eventsLieInsideTheWindow() throws Exception {
        long before = System.currentTimeMillis();
        assertInstanceOf(DevDataOutcome.Finished.class, run(request(1_000, 3), DevDataListener.NONE));
        long after = System.currentTimeMillis();

        for (StoredEvent event : of(NODE)) {
            // Ids sit on the one-second grid, so an event may be stored up to a second before its time.
            assertTrue(event.epochMillis() >= before - 3 * DAY_MILLIS - 1_000, event.toString());
            assertTrue(event.epochMillis() <= after - 60_000, event.toString());
        }
    }

    private void devMode(boolean on) {
        TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: " + on + "\n");
        TestPlugins.reload();
    }

    private @NotNull DevDataOutcome run(@NotNull DevDataRequest request, @NotNull DevDataListener listener)
            throws Exception {
        return service.start(request, listener).get(60, TimeUnit.SECONDS);
    }

    private static @NotNull DevDataRequest request(int count, int days) {
        return new DevDataRequest(count, days, List.of(), "test");
    }

    private @NotNull List<StoredEvent> all() throws SQLException {
        return TestPlugins.bean(EventStore.class).find(ready.home(), LookupQuery.at(new Area.Everywhere()),
                new ShardScope.All(), 0, 1_000_000, false);
    }

    private @NotNull List<StoredEvent> of(@NotNull String node) throws SQLException {
        return all().stream().filter(event -> event.server().equals(node)).toList();
    }

    private @NotNull Set<String> servers() throws SQLException {
        return TestPlugins.bean(EventStore.class).servers(ready.home()).stream().map(ServerNode::name)
                .collect(Collectors.toSet());
    }

    private static @NotNull DevDataListener holding(@NotNull CountDownLatch opened, @NotNull CountDownLatch hold) {
        return new DevDataListener() {
            @Override
            public void started(@NotNull DevDataProgress progress) {
                opened.countDown();
                await(hold);
            }
        };
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
