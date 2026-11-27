package gg.moonrise.hawkeye.core.storage.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Packing while the hot table keeps changing: a pass reads and encodes without a lock and stores in one
 * short transaction, so other writers commit meanwhile (on SQLite a deferred transaction failed with
 * {@code SQLITE_BUSY_SNAPSHOT} then), and a pass whose rows changed before it stored them stores nothing.
 */
public class ArchiveConcurrencyTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final long NOW = T0 + 86_400_000L * 10;
    private static final ShardScope ALL = new ShardScope.All();
    private static final LookupQuery EVERYTHING = LookupQuery.at(new Area.Everywhere());
    private static final Actor ALEX = Actor.player(new UUID(0, 1), "alex");

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private EventStore store;
    private Archive archive;
    private ServerNode node;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        archive = Archives.create();
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive);
        node = harness.register("survival", "survival");
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void packingSucceedsWhileAnotherThreadKeepsCommittingEventsAndEveryEventIsStoredOnce()
            throws Exception {
        ArchiveSettings settings = new ArchiveSettings().withPacking(16_384, 60, 1);
        Set<Long> expected = new HashSet<>();
        for (int from = 0; from < 20_000; from += 2_000) {
            List<PendingEvent> batch = new ArrayList<>();
            for (int i = from; i < from + 2_000; i++) {
                batch.add(event(T0 + i * 10L, 0));
            }
            write(batch, expected);
        }
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger batches = new AtomicInteger();
        List<PendingEvent> written = new ArrayList<>();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<?> writing = writer.submit(() -> {
            int next = 0;
            while (!done.get()) {
                // Late events inside the packed id range, numbered apart from the first ones.
                List<PendingEvent> batch = new ArrayList<>();
                for (int i = 0; i < 100; i++, next++) {
                    batch.add(event(T0 + next % 20_000 * 10L, 1 + next / 20_000));
                }
                store.write(harness.shard(), node, "survival", batch);
                written.addAll(batch);
                batches.incrementAndGet();
            }
            return null;
        });
        int first;
        int second;
        int during;
        try {
            while (batches.get() == 0 && !writing.isDone()) {
                Thread.onSpinWait();
            }
            int before = batches.get();
            first = archive.compact(harness.shard(), settings, NOW);
            during = batches.get() - before;
            second = archive.compact(harness.shard(), settings, NOW);
        } finally {
            done.set(true);
            writing.get(30, TimeUnit.SECONDS);
            writer.shutdownNow();
        }
        written.forEach(event -> expected.add(EventIds.fromStamp(event.stamp(), node.node())));

        assertTrue(during > 0, "the other thread committed while the pass ran");
        assertEquals(16_384, first, "a full segment");
        assertTrue(second > 0, "the rest, old enough for a short segment");
        assertEquals(first + second, archive.totals(harness.shard()).events());
        List<Long> found = store.find(harness.shard(), EVERYTHING, ALL, 0, 1_000_000, true).stream()
                .map(StoredEvent::id).toList();
        assertEquals(expected.size(), found.size(), "every event once, across both tiers");
        assertEquals(expected, new HashSet<>(found));
        assertEquals(expected.size(), store.count(harness.shard(), EVERYTHING, ALL));
    }

    @Test
    public void aRollbackFlagSetAfterTheRowsWereReadIsNotLost() throws SQLException {
        ArchiveSettings settings = new ArchiveSettings().withPacking(500, 60, 1);
        write(oldEvents(600), new HashSet<>());
        PreparedSegment stale = archive.prepareCompaction(harness.shard(), settings, NOW).orElseThrow();
        long flagged = stale.rows().get(10).id();

        assertEquals(1, store.setRolledBack(harness.shard(), List.of(flagged), true));
        assertFalse(archive.commitCompaction(harness.shard(), stale), "the pass that saw the old flag stores nothing");
        assertEquals(0, archive.totals(harness.shard()).segments());
        assertEquals(600, store.count(harness.shard(), EVERYTHING, ALL));

        assertEquals(500, archive.compact(harness.shard(), settings, NOW));
        assertEquals(500, archive.totals(harness.shard()).events());
        List<Long> rolledBack = store.find(harness.shard(), EVERYTHING.withRollbackState(RollbackState.ROLLED_BACK),
                ALL, 0, 1_000, true).stream().map(StoredEvent::id).toList();
        assertEquals(List.of(flagged), rolledBack, "the archived event reads as rolled back");
        assertEquals(600, store.count(harness.shard(), EVERYTHING, ALL));
    }

    @Test
    public void aRowPurgedAfterTheRowsWereReadIsNotResurrected() throws SQLException {
        ArchiveSettings settings = new ArchiveSettings().withPacking(500, 60, 1);
        write(oldEvents(600), new HashSet<>());
        PreparedSegment stale = archive.prepareCompaction(harness.shard(), settings, NOW).orElseThrow();
        long purged = stale.rows().get(42).id();

        int deleted = harness.database().read(connection -> new EventRepository().deleteIds(connection,
                harness.database().sql(), List.of(purged)));
        assertEquals(1, deleted);
        assertFalse(archive.commitCompaction(harness.shard(), stale), "the pass that read the purged row stores nothing");
        assertEquals(0, archive.totals(harness.shard()).segments());

        assertEquals(500, archive.compact(harness.shard(), settings, NOW));
        List<Long> found = store.find(harness.shard(), EVERYTHING, ALL, 0, 1_000, true).stream()
                .map(StoredEvent::id).toList();
        assertEquals(599, found.size());
        assertFalse(found.contains(purged), "the purged event is in neither tier");
    }

    @Test
    public void anEventWrittenIntoThePackedRangeAfterTheReadStaysHot() throws SQLException {
        ArchiveSettings settings = new ArchiveSettings().withPacking(500, 60, 1);
        write(oldEvents(500), new HashSet<>());
        PreparedSegment prepared = archive.prepareCompaction(harness.shard(), settings, NOW).orElseThrow();
        PendingEvent late = event(T0 + 250 * 10L, 1);
        write(List.of(late), new HashSet<>());

        assertTrue(archive.commitCompaction(harness.shard(), prepared), "a late write does not make the pass stale");
        assertEquals(500, archive.totals(harness.shard()).events());
        List<Long> found = store.find(harness.shard(), EVERYTHING, ALL, 0, 1_000, true).stream()
                .map(StoredEvent::id).toList();
        assertEquals(501, found.size());
        assertEquals(1, found.stream().filter(id -> id == EventIds.fromStamp(late.stamp(), node.node())).count());
    }

    private void write(List<PendingEvent> batch, Set<Long> ids) throws SQLException {
        store.write(harness.shard(), node, "survival", batch);
        batch.forEach(event -> ids.add(EventIds.fromStamp(event.stamp(), node.node())));
    }

    private static List<PendingEvent> oldEvents(int count) {
        List<PendingEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(event(T0 + i * 10L, 0));
        }
        return events;
    }

    private static PendingEvent event(long millis, int sequence) {
        int spot = (int) ((millis - T0) / 10);
        return new PendingEvent(EventIds.stamp(millis, sequence), "world",
                BlockPositions.pack(spot % 64, 64, spot / 64 % 64), ALEX, Action.BLOCK_PLACE, "minecraft:stone",
                "minecraft:air", 0, null);
    }
}
