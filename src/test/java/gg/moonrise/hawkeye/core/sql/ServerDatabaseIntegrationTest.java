package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.config.file.StorageType;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRange;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRow;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.importer.ImportStateRepository;
import gg.moonrise.hawkeye.core.repository.index.PlaceIndexRepository;
import gg.moonrise.hawkeye.core.repository.index.SegmentIndexRepository;
import gg.moonrise.hawkeye.core.repository.network.JobRepository;
import gg.moonrise.hawkeye.core.repository.network.LeaseRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.storage.archive.Archive;
import gg.moonrise.hawkeye.core.storage.archive.Archives;
import gg.moonrise.hawkeye.core.storage.archive.PreparedSegment;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.core.storage.importer.ImportStore;
import gg.moonrise.hawkeye.core.storage.index.SegmentIndex;
import gg.moonrise.hawkeye.core.storage.segment.ByteRange;
import gg.moonrise.hawkeye.core.storage.shard.Dictionaries;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.importer.CheckpointResult;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.storage.SegmentTotals;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SQLite storage scenarios against a real MySQL/MariaDB or PostgreSQL server, ending with an import's
 * checkpointed writes ({@code import_state}, {@link ImportStore}). Opt-in: set
 * {@code HAWKEYE_TEST_MYSQL} and/or {@code HAWKEYE_TEST_POSTGRES} to {@code scheme://user:password@host:port/database}
 * (an empty database the user may create tables in). Without them the tests are skipped. Each run uses
 * its own table prefix and drops its tables afterwards.
 */
public class ServerDatabaseIntegrationTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;

    @TempDir
    private Path folder;

    @ParameterizedTest
    @ValueSource(strings = {"HAWKEYE_TEST_MYSQL", "HAWKEYE_TEST_POSTGRES"})
    public void storageWorksOnServerEngines(String variable) throws Exception {
        String url = System.getenv(variable);
        assumeTrue(url != null && !url.isBlank(), variable + " is not set");
        String prefix = "hk" + Long.toHexString(System.nanoTime()).substring(0, 6) + "_";
        Database database = new DatabaseFactory(name -> Optional.empty()).open("it", settings(URI.create(url), prefix),
                folder);
        try {
            run(database);
        } finally {
            drop(database);
            database.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HAWKEYE_TEST_MYSQL", "HAWKEYE_TEST_POSTGRES"})
    public void aRefusedLoginShowsNoCredential(String variable) throws Exception {
        String url = System.getenv(variable);
        assumeTrue(url != null && !url.isBlank(), variable + " is not set");
        URI uri = URI.create(url);
        String user = "hk_nobody_" + Long.toHexString(System.nanoTime());
        String password = "hk-wrong-password-4711";
        StorageSettings base = settings(uri, "hk_");
        StorageSettings settings = StorageSettings.server(base.type(), uri.getHost(), uri.getPort(),
                uri.getPath().substring(1), "${HK_TEST_USER}", "${HK_TEST_PASSWORD}", "hk_");
        Map<String, String> environment = Map.of("HK_TEST_USER", user, "HK_TEST_PASSWORD", password);
        // A short pool timeout: the login is refused at once, the pool then gives up after a second.
        Field timeout = StorageSettings.class.getDeclaredField("connectionTimeoutMillis");
        timeout.setAccessible(true);
        timeout.setLong(settings, 1_000);
        Database database = new DatabaseFactory(name -> Optional.ofNullable(environment.get(name)))
                .open("it", settings, folder);
        try {
            SQLException failure = assertThrows(SQLException.class,
                    () -> database.read(connection -> connection.isValid(1)));
            StringWriter printed = new StringWriter();
            failure.printStackTrace(new PrintWriter(printed));
            StringBuilder text = new StringBuilder(printed.toString());
            for (Throwable next : failure) {
                text.append('\n').append(next);
            }
            String all = text.toString();
            // The server did answer: MariaDB "Access denied for user", PostgreSQL "authentication failed" or
            // "role ... does not exist" (trust authentication refuses an unknown role). MySQL 8 answers an
            // unknown user as a decoy account whose plugin it picks at random; when that is sha256_password,
            // which Connector/J does not implement, the driver stops at "Client does not support
            // authentication protocol requested by server" before any "Access denied".
            assertTrue(all.contains("Access denied") || all.contains("authentication failed")
                    || all.contains("does not exist") || all.contains("does not support authentication protocol"),
                    all);
            assertFalse(all.contains(user), all);
            assertFalse(all.contains(password), all);
        } finally {
            database.close();
        }
    }

    private static void run(Database database) throws SQLException {
        assertInstanceOf(MigrationResult.Migrated.class, migrate(database));
        assertInstanceOf(MigrationResult.UpToDate.class, migrate(database));

        ServerRepository servers = new ServerRepository();
        ServerNode survival = null;
        for (int i = 0; i < 5; i++) {
            survival = database.read(connection -> servers.register(connection, database.sql(), "survival",
                    "survival", System.currentTimeMillis()));
        }
        ServerNode creative = database.read(connection -> servers.register(connection, database.sql(), "creative",
                "creative", System.currentTimeMillis()));
        assertEquals(survival.node() + 1, creative.node(), "re-registering must not consume node ids");

        Shard shard = new Shard("it", database, new Dictionaries(new DictionaryRepository(), new ActorRepository(),
                servers), true);
        // Busy after two segments, so the four segments below give chunks of both kinds.
        Archive archive = Archives.create(new SegmentRepository(), 2);
        EventStore store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive);
        List<PendingEvent> events = new ArrayList<>();
        Actor alex = Actor.player(new UUID(0, 1), "Alex");
        for (int i = 0; i < 2_000; i++) {
            events.add(new PendingEvent(EventIds.stamp(T0 + i * 1000L, 0), "world",
                    BlockPositions.pack(i % 100 - 50, 64 + i % 7, i / 100 - 10), i % 3 == 0 ? Actor.source("tnt") : alex,
                    i % 2 == 0 ? Action.BLOCK_BREAK : Action.BLOCK_PLACE,
                    i % 5 == 0 ? "minecraft:oak_log[axis=y]" : "minecraft:stone", "minecraft:air", 0,
                    i % 50 == 0 ? new byte[] {1, 'h', 'i'} : null));
        }
        store.write(shard, survival, "survival", events);
        // A retried batch must not duplicate rows. (MySQL drivers report matched rather than inserted rows
        // for ON DUPLICATE KEY UPDATE, so the row count is checked, not the insert's update count.)
        store.write(shard, survival, "survival", events);

        ShardScope scope = new ShardScope.Scopes(Set.of("survival"));
        assertEquals(2_000, store.count(shard, LookupQuery.at(new Area.Everywhere()), scope));
        LookupQuery box = LookupQuery.at(new Area.Box("world", -20, 60, -5, 20, 70, 5));
        long inBox = events.stream().filter(event -> ((Area.Box) box.area()).contains(
                BlockPositions.x(event.position()), BlockPositions.y(event.position()),
                BlockPositions.z(event.position()))).count();
        assertEquals(inBox, store.count(shard, box, scope));
        LookupQuery logs = new LookupQuery(box.servers(), new Area.Everywhere(), box.time(), Set.of("alex"),
                Set.of(), Set.of(), Set.of("minecraft:oak_log"), Set.of(),
                RollbackState.ANY);
        long expectedLogs = events.stream().filter(event -> event.actor().equals(alex)
                && event.subject().startsWith("minecraft:oak_log")).count();
        assertEquals(expectedLogs, store.count(shard, logs, scope));

        List<Long> before = ids(store.find(shard, box, scope, 0, 10_000, true));
        List<Long> oldestFirst = ids(store.find(shard, box, scope, 0, 10_000, false));
        // Segments of 400 events; the newest 400 have not settled yet.
        ArchiveSettings packing = new ArchiveSettings().withPacking(400, 60, 1);
        long now = T0 + 1_600_000 + 60_000;
        // A pass whose rows changed after it read them stores nothing (the locking read of the check).
        PreparedSegment stale = archive.prepareCompaction(shard, packing, now).orElseThrow();
        List<Long> changed = List.of(stale.rows().get(7).id());
        store.setRolledBack(shard, changed, true);
        assertFalse(archive.commitCompaction(shard, stale), "a changed flag makes the pass stale");
        assertEquals(0, archive.totals(shard).segments());
        store.setRolledBack(shard, changed, false);
        int packed = 0;
        int pass;
        while ((pass = archive.compact(shard, packing, now)) > 0) {
            packed += pass;
        }
        assertEquals(1_600, packed, "four segments of 400 settled events");
        SegmentTotals totals = archive.totals(shard);
        assertEquals(new SegmentTotals(1_600, 4, totals.bytes()), totals);
        assertTrue(totals.bytes() > 0 && totals.bytes() < 1_600 * 8, "bytes " + totals.bytes());
        assertEquals(before, ids(store.find(shard, box, scope, 0, 10_000, true)));
        assertEquals(oldestFirst, ids(store.find(shard, box, scope, 0, 10_000, false)));
        assertEquals(before, pages(store, shard, box, scope));
        assertEquals(expectedLogs, store.count(shard, logs, scope));
        assertPlaceIndex(database, shard, store, archive, scope, events);
        assertRangeWalk(database);
        assertEquals(before.size(), store.setRolledBack(shard, before, true));
        assertEquals(before.size(), store.count(shard, box.withRollbackState(RollbackState.ROLLED_BACK), scope));

        // A byte range of a body, read with substr on the server's blob type.
        long segment = database.read(connection -> new SegmentRepository().candidates(connection,
                database.sql(), EventFilter.everything(0, Long.MAX_VALUE), 0, null, 1, false).getFirst().id());
        byte[] body = database.read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT body FROM " + database.tables().segment()
                            + " WHERE id = " + segment)) {
                rows.next();
                return rows.getBytes(1);
            }
        });
        List<byte[]> ranges = database.read(connection -> new SegmentRepository().readRanges(connection,
                database.sql(), segment, List.of(new ByteRange(3, 10), new ByteRange(0, 3), new ByteRange(40, 5))));
        assertArrayEquals(Arrays.copyOfRange(body, 3, 13), ranges.get(0));
        assertArrayEquals(Arrays.copyOfRange(body, 0, 3), ranges.get(1));
        assertArrayEquals(Arrays.copyOfRange(body, 40, 45), ranges.get(2));

        while (store.purgeBatch(shard, T0 + 1_000_000, List.of(), 300) > 0) {
            // Purge the oldest half: two whole segments, one straddling the bound.
        }
        assertEquals(1_000, store.count(shard, LookupQuery.at(new Area.Everywhere()), scope));
        assertEquals(before.stream().filter(id -> id >= EventIds.floor(T0 + 1_000_000)).count(),
                store.count(shard, box.withRollbackState(RollbackState.ROLLED_BACK), scope),
                "flags survive the rewrite of the straddling segment");

        JobRepository jobs = new JobRepository();
        long job = database.read(connection -> jobs.insert(connection, database.sql(), "creative", JobKind.ROLLBACK,
                "survival", "Alex", "v=1", 1));
        boolean claimed = database.read(connection -> jobs.claim(connection, database.sql(), job, 2));
        assertTrue(claimed);

        LeaseRepository leases = new LeaseRepository();
        assertTrue(leases.tryAcquire(database, "archive", "survival", 1_000, 10_000));
        assertTrue(leases.tryAcquire(database, "archive", "survival", 2_000, 10_000), "the holder renews");
        assertFalse(leases.tryAcquire(database, "archive", "creative", 3_000, 10_000), "another server waits");
        assertTrue(leases.tryAcquire(database, "archive", "creative", 13_000, 10_000), "an expired lease moves");
        assertTrue(leases.release(database, "archive", "creative"));

        assertImport(database, shard, store, creative);
    }

    /**
     * An import's checkpoint on a server engine: the import node is a plain insert that a taken name
     * rejects without consuming a node id, a segment and its compare-and-set checkpoint commit together,
     * and a stale revision rolls the segment back.
     */
    private static void assertImport(Database database, Shard shard, EventStore store, ServerNode creative)
            throws SQLException {
        ImportStore imports = new ImportStore(new ImportStateRepository(), new ServerRepository(), store);
        String source = "coreprotect:survival:1735689600:25.0";
        ImportState state = imports.begin(shard, source, "survival", "survival", "rowid", 1_000);
        assertEquals(ImportState.fresh("survival-coreprotect", "rowid", 1_000), state);
        ServerNode node = imports.node(shard, state);
        assertTrue(node.imported());
        assertEquals(creative.node() + 1, node.node());
        assertEquals(state, imports.begin(shard, source, "survival", "survival", "rowid", 2_000));
        assertEquals(Optional.empty(), database.read(connection -> new ServerRepository().registerImport(connection,
                database.sql(), "survival-coreprotect", "survival")), "the name is taken");
        assertEquals("survival-coreprotect-2", imports.begin(shard, source + "b", "survival", "survival", "rowid",
                3_000).node());
        assertEquals(creative.node() + 2, imports.node(shard, imports.load(shard, source + "b").orElseThrow()).node(),
                "a taken name consumed no node id");

        ShardScope scope = new ShardScope.Scopes(Set.of("survival"));
        LookupQuery everything = LookupQuery.at(new Area.Everywhere());
        long before = store.count(shard, everything, scope);
        LookupQuery rolledBack = everything.withRollbackState(RollbackState.ROLLED_BACK);
        long rolledBackBefore = store.count(shard, rolledBack, scope);
        long slot = EventIds.slot(EventIds.floor(T0 + 5_000_000));
        Actor steve = Actor.player(new UUID(0, 2), "Steve");
        List<PendingEvent> batch = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch.add(new PendingEvent(EventIds.slotStamp(slot, i), "world", BlockPositions.pack(i, 64, 0), steve,
                    Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air", 0, null, i == 0));
        }
        ImportState next = new ImportState(state.revision(), state.node(), state.order(), Map.of(SourceTable.BLOCK,
                new SourceCursor(1_767_225_600, 3)), slot, 3, 0, Map.of(SkipReason.NO_EQUIVALENT, 1L), false,
                state.startedMillis(), 4_000);
        CheckpointResult saved = imports.write(shard, node, "survival", batch, Runnable::run, source, next);
        assertEquals(new CheckpointResult.Saved(next.withRevision(2, 4_000)), saved);
        assertEquals(next.withRevision(2, 4_000), imports.load(shard, source).orElseThrow());
        assertEquals(before + 3, store.count(shard, everything, scope));
        assertEquals(rolledBackBefore + 1, store.count(shard, rolledBack, scope), "imported as rolled back");
        List<StoredEvent> imported = store.find(shard, new LookupQuery(new ServerScope.Local(), new Area.Everywhere(),
                TimeWindow.ALL, Set.of("steve"), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY), scope, 0,
                10, false);
        assertEquals(List.of("survival-coreprotect", "survival-coreprotect", "survival-coreprotect"),
                imported.stream().map(StoredEvent::server).toList());
        assertEquals(List.of(true, false, false), imported.stream().map(StoredEvent::rolledBack).toList());

        List<PendingEvent> again = batch.stream().map(event -> event.withStamp(EventIds.slotStamp(slot + 1,
                EventIds.rank(EventIds.fromStamp(event.stamp(), 0))))).toList();
        assertEquals(new CheckpointResult.Superseded(), imports.write(shard, node, "survival", again, Runnable::run,
                source, next), "revision 1 is stale");
        assertEquals(before + 3, store.count(shard, everything, scope), "the stale segment rolled back");
        assertEquals(3, assertInstanceOf(CheckpointResult.Saved.class, imports.save(shard, source,
                next.withRevision(2, 5_000))).state().revision());
        assertEquals(new CheckpointResult.Superseded(), imports.save(shard, source, next.withRevision(2, 6_000)));
    }

    /**
     * The segment index on a server engine: rows of all three tables exist, block and player lookups
     * agree with the events, a block never touched finds nothing, and emptying the index and catching up
     * writes the same rows byte for byte.
     */
    private static void assertPlaceIndex(Database database, Shard shard, EventStore store, Archive archive,
            ShardScope scope, List<PendingEvent> events) throws SQLException {
        Map<String, byte[]> packed = placeRows(database);
        assertTrue(packed.keySet().stream().anyMatch(key -> key.startsWith(database.tables().blockSegment())),
                "busy chunks have rows: " + packed.keySet());
        assertTrue(packed.keySet().stream().filter(key -> key.startsWith(database.tables().regionSegment())).count() > 0);
        assertEquals(2, packed.keySet().stream().filter(key -> key.startsWith(database.tables().actorSegment()))
                .count(), "a row for each of the two actors: " + packed.keySet());
        LookupQuery alex = new LookupQuery(new ServerScope.Local(), new Area.Everywhere(), TimeWindow.ALL,
                Set.of("alex"), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
        long byAlex = events.stream().filter(event -> event.actor().name().equals("Alex")).count();
        assertEquals(byAlex, store.count(shard, alex, scope), "a player lookup agrees with the events");
        assertEquals(Math.min(10, byAlex), store.find(shard, alex, scope, 0, 10, true).size());
        assertEquals(0, store.count(shard, LookupQuery.at(new Area.Block("world", 0, 0, 0)), scope));
        for (int i : new int[] {3, 517, 1_210, 1_599}) {
            long position = events.get(i).position();
            LookupQuery block = LookupQuery.at(new Area.Block("world", BlockPositions.x(position),
                    BlockPositions.y(position), BlockPositions.z(position)));
            long expected = events.stream().filter(event -> event.position() == position).count();
            assertEquals(expected, store.count(shard, block, scope), "block of event " + i);
        }
        database.transaction(connection -> {
            new SegmentIndexRepository().reset(connection, database.sql());
            return null;
        });
        assertEquals(Map.of(), placeRows(database));
        assertEquals(new SegmentIndex.CatchUp(4, false), archive.catchUpIndex(shard, 10));
        Map<String, byte[]> caughtUp = placeRows(database);
        assertEquals(packed.keySet(), caughtUp.keySet());
        packed.forEach((key, body) -> assertArrayEquals(body, caughtUp.get(key), key));
    }

    /**
     * The walk of id ranges a read naming many segments uses, on a server engine: page after page it
     * gives the segments in the candidates' newest-first order.
     */
    private static void assertRangeWalk(Database database) throws SQLException {
        SegmentRepository repository = new SegmentRepository();
        EventFilter everything = EventFilter.everything(0, Long.MAX_VALUE);
        List<Long> newestFirst = database.read(connection -> repository.candidates(connection, database.sql(),
                everything, 0, null, 32, true)).stream().map(SegmentRow::id).toList();
        List<Long> walked = new ArrayList<>();
        SegmentRange after = null;
        while (true) {
            SegmentRange from = after;
            List<SegmentRange> page = database.read(connection -> repository.newestRanges(connection, database.sql(),
                    everything, 0, from, 3));
            page.forEach(range -> walked.add(range.id()));
            if (page.size() < 3) {
                break;
            }
            after = page.getLast();
        }
        assertEquals(4, walked.size(), "two pages of three name the four segments");
        assertEquals(newestFirst, walked);
    }

    /** Every segment index row's body, by table, key and newest segment. */
    private static Map<String, byte[]> placeRows(Database database) throws SQLException {
        return database.read(connection -> {
            Map<String, byte[]> out = new TreeMap<>();
            for (PlaceIndexRepository.Kind kind : PlaceIndexRepository.Kind.values()) {
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT world, " + kind.keyColumn() + ", newest, body "
                                + "FROM " + kind.table(database.sql()))) {
                    while (rows.next()) {
                        out.put(kind.table(database.sql()) + " " + rows.getInt(1) + " " + rows.getLong(2) + " "
                                + rows.getLong(3), rows.getBytes(4));
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT actor, newest, body FROM "
                            + database.tables().actorSegment())) {
                while (rows.next()) {
                    out.put(database.tables().actorSegment() + " " + rows.getInt(1) + " " + rows.getLong(2),
                            rows.getBytes(3));
                }
            }
            return out;
        });
    }

    /** Newest-first pages of 37 through both tiers. */
    private static List<Long> pages(EventStore store, Shard shard, LookupQuery query, ShardScope scope)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<StoredEvent> page = store.find(shard, query, scope, cursor, 37, true);
            page.forEach(event -> ids.add(event.id()));
            if (page.size() < 37) {
                return ids;
            }
            cursor = page.getLast().id();
        }
    }

    private static List<Long> ids(List<StoredEvent> events) {
        return events.stream().map(StoredEvent::id).toList();
    }

    private static MigrationResult migrate(Database database) throws SQLException {
        return database.read(connection -> Migrations.apply(connection, database.dialect(), database.tables()));
    }

    private static StorageSettings settings(URI uri, String prefix) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        StorageType type = scheme.startsWith("postgres") ? StorageType.POSTGRESQL
                : scheme.equals("mariadb") ? StorageType.MARIADB : StorageType.MYSQL;
        String[] user = uri.getUserInfo() == null ? new String[] {"", ""} : uri.getUserInfo().split(":", 2);
        return StorageSettings.server(type, uri.getHost(), uri.getPort(), uri.getPath().substring(1), user[0],
                user.length > 1 ? user[1] : "", prefix);
    }

    private static void drop(Database database) throws SQLException {
        Tables t = database.tables();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of(t.importState(), t.regionSegment(), t.blockSegment(), t.actorSegment(), t.segmentIndex(), t.legacyPlaceRegion(), t.legacyPlaceChunk(), t.legacyPlaceState(), t.segmentFlag(), t.segment(), t.lease(), t.job(), t.event(), t.type(),
                        t.actor(), t.world(), t.server(), t.schemaVersion())) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
            return null;
        });
    }
}
