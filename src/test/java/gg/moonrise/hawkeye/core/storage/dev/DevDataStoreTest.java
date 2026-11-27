package gg.moonrise.hawkeye.core.storage.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.queue.id.IdAssigner;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.archive.Archives;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DevDataStoreTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 30;
    private static final ShardScope SURVIVAL = new ShardScope.Scopes(Set.of("survival"));
    private static final Actor ALEX = Actor.player(new UUID(0, 1), "Alex");

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private EventStore events;
    private DevDataStore store;
    private Shard home;
    private ServerNode live;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        events = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), Archives.create());
        store = new DevDataStore(new ServerRepository(), events);
        home = harness.shard();
        live = harness.register("survival", "survival");
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void everyRunGetsTheNextFreeNode() throws SQLException {
        ServerNode first = store.registerNode(home, "survival", "survival").orElseThrow();
        assertEquals(new ServerNode(2, "survival-dev", "survival", 0), first);
        assertTrue(first.imported());

        harness.register("survival-dev-2", "survival");
        ServerNode second = store.registerNode(home, "survival", "survival").orElseThrow();
        assertEquals("survival-dev-3", second.name(), "-2 is a running server's name");
        assertTrue(second.imported());
        assertEquals(4, events.servers(home).size());
    }

    @Test
    public void aDatabaseWithoutAFreeNodeIdRegistersNothing() throws SQLException {
        harness.database().read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO "
                    + harness.database().sql().tables().server() + " (id, name, scope, last_seen) VALUES (?, ?, ?, ?)")) {
                statement.setInt(1, EventIds.MAX_NODE);
                statement.setString(2, "last");
                statement.setString(3, "survival");
                statement.setLong(4, 0);
                return statement.executeUpdate();
            }
        });
        int before = events.servers(home).size();

        assertEquals(Optional.empty(), store.registerNode(home, "survival", "survival"));
        assertEquals(before, events.servers(home).size());
    }

    @Test
    public void aBatchIsArchivedAndFoundUnderItsNode() throws SQLException {
        ServerNode node = store.registerNode(home, "survival", "survival").orElseThrow();

        assertEquals(5, store.write(home, node, "survival", numbered(batch(T0, 5)), Runnable::run));

        assertEquals(5, events.count(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL));
        List<StoredEvent> found = events.find(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL, 0, 10, false);
        assertEquals(5, found.size());
        assertTrue(found.stream().allMatch(event -> event.server().equals("survival-dev")), found.toString());
    }

    @Test
    public void twoRunsOverTheSameSecondsNeverShareAnId() throws SQLException {
        ServerNode first = store.registerNode(home, "survival", "survival").orElseThrow();
        ServerNode second = store.registerNode(home, "survival", "survival").orElseThrow();
        List<PendingEvent> batch = numbered(batch(T0, 40));

        store.write(home, first, "survival", batch, Runnable::run);
        store.write(home, second, "survival", batch, Runnable::run);

        List<StoredEvent> found = events.find(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL, 0, 1_000, false);
        assertEquals(80, events.count(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL));
        assertEquals(80, found.stream().map(StoredEvent::id).distinct().count());
    }

    @Test
    public void olderHotEventsAndANewerDevSegmentReadInIdOrder() throws SQLException {
        // Live events stay hot; the fake ones reach the archive at once, though they are newer.
        events.write(home, live, "survival", numbered(batch(T0, 3)));
        ServerNode node = store.registerNode(home, "survival", "survival").orElseThrow();
        store.write(home, node, "survival", numbered(batch(T0 + 60_000, 4)), Runnable::run);

        List<StoredEvent> found = events.find(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL, 0, 100, true);

        assertEquals(7, found.size());
        List<StoredEvent> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingLong(StoredEvent::id).reversed());
        assertEquals(sorted, found, "newest first");
        assertEquals(List.of("survival-dev", "survival-dev", "survival-dev", "survival-dev", "survival", "survival",
                "survival"), found.stream().map(StoredEvent::server).toList());
    }

    private static @NotNull List<PendingEvent> numbered(@NotNull List<PendingEvent> batch) {
        IdAssigner assigner = new IdAssigner();
        assigner.add(batch);
        return assigner.take(Long.MAX_VALUE / 2, true);
    }

    private static @NotNull List<PendingEvent> batch(long millis, int count) {
        List<PendingEvent> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new PendingEvent(EventIds.stamp(millis + i * 10L, i), "world", BlockPositions.pack(i, 64, 0),
                    ALEX, Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air", 0, null));
        }
        return batch;
    }
}
