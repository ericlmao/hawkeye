package gg.moonrise.hawkeye.core.storage.event;

import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.core.sql.SqlWork;
import gg.moonrise.hawkeye.core.storage.archive.Archive;
import gg.moonrise.hawkeye.core.storage.archive.PreparedSegment;
import gg.moonrise.hawkeye.core.storage.shard.Dictionaries;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.EventCount;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The event operations the services and the writer use, on any shard: writing a batch, reading
 * pages, counting, flagging and purging. Owns all connection and transaction handling around the
 * repositories, so no service touches SQL. Every read merges the hot {@code event} table with the
 * packed archive ({@link Archive}), so callers never see which tier holds an event.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EventStore {

    /**
     * Archive segments deleted or rewritten per purge batch. A rewrite decodes and re-encodes up to
     * {@code archive.segment-events} events, so a batch stays at a few seconds.
     */
    public static final int PURGE_SEGMENTS = 8;

    private final @NotNull EventRepository eventRepository;
    private final @NotNull QueryResolver queryResolver;
    private final @NotNull Archive archive;

    /**
     * Writes a batch: dictionary values are interned first in auto-commit mode (so an id never refers
     * to a rolled-back row), then every event is inserted in one transaction. Idempotent: retrying a
     * batch inserts nothing twice.
     *
     * @param home   the home shard
     * @param node   this server's registration
     * @param scope  this server's world scope
     * @param events the batch
     * @return the number of rows inserted
     * @throws SQLException when interning or inserting fails
     */
    public int write(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events) throws SQLException {
        if (events.isEmpty()) {
            return 0;
        }
        Database database = home.database();
        Sql sql = database.sql();
        List<EventRow> rows = resolve(home, node, scope, events, true);
        return database.transaction(connection -> eventRepository.insert(connection, sql, rows));
    }

    /**
     * Writes a batch straight into the archive as one segment, resolved exactly like {@link #write}
     * (bulk imports, benchmarks). The segment is encoded before its transaction begins, which then only
     * stores it. The events must be older than anything the shard will still pack, and a retry writes the
     * segment again: this is not idempotent.
     *
     * @param home     the home shard
     * @param node     the registration of the server the events belong to
     * @param scope    that server's world scope
     * @param events   the batch, in any order, with distinct stamps
     * @param executor where the segment's frames are compressed (a pool for a bulk import)
     * @return the number of events archived
     * @throws SQLException when interning or writing fails
     */
    public int writeArchived(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events, @NotNull Executor executor) throws SQLException {
        if (events.isEmpty()) {
            return 0;
        }
        List<EventRow> rows = resolve(home, node, scope, events, true);
        PreparedSegment prepared = archive.prepare(home, rows, executor);
        home.database().transaction(connection -> archive.store(connection, home, prepared));
        return rows.size();
    }

    /**
     * Writes imported history straight into the archive as one segment, like {@link #writeArchived},
     * with two differences: actors are interned without renaming one the shard already knows (the
     * source's name of a player may be older than the one this shard has seen), and {@code alongside}
     * runs on the same connection inside the segment's transaction, after the segment is stored (it is
     * encoded before that transaction begins). When
     * {@code alongside} throws, the segment is rolled back with it, so a checkpoint written there and
     * the segment always agree.
     *
     * @param home      the home shard
     * @param node      the import node the events belong to
     * @param scope     the world scope the events are stored under
     * @param events    the batch, in any order, with distinct stamps
     * @param executor  where the segment's frames are compressed
     * @param alongside work committed with the segment (the import checkpoint)
     * @return the number of events archived
     * @throws SQLException when interning, writing or {@code alongside} fails
     */
    public int writeImported(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events, @NotNull Executor executor, @NotNull SqlWork<?> alongside)
            throws SQLException {
        if (events.isEmpty()) {
            home.database().transaction(alongside);
            return 0;
        }
        List<EventRow> rows = resolve(home, node, scope, events, false);
        PreparedSegment prepared = archive.prepare(home, rows, executor);
        home.database().transaction(connection -> {
            archive.store(connection, home, prepared);
            return alongside.run(connection);
        });
        return rows.size();
    }

    /**
     * The newest id stored in a shard, hot or archived, of any server. The writer starts numbering after
     * its slot, so a restart never reuses a slot whose ranks are already stored.
     *
     * @param home the shard
     * @return the id, or 0 when the shard holds no event
     * @throws SQLException when reading fails
     */
    public long newestId(@NotNull Shard home) throws SQLException {
        Database database = home.database();
        Sql sql = database.sql();
        return database.read(connection -> Math.max(eventRepository.newestId(connection, sql),
                archive.newestId(connection, sql)));
    }

    /**
     * Reads a page of events.
     *
     * @param shard       the shard
     * @param query       the query
     * @param scope       which of the shard's worlds
     * @param cursor      the exclusive keyset bound from the previous page, 0 for the first
     * @param limit       the most events
     * @param newestFirst the order
     * @return the events in that order; empty when nothing matches
     * @throws SQLException when reading fails
     */
    public @NotNull List<StoredEvent> find(@NotNull Shard shard, @NotNull LookupQuery query,
            @NotNull ShardScope scope, long cursor, int limit, boolean newestFirst) throws SQLException {
        return shard.database().read(connection -> {
            Optional<EventFilter> filter = queryResolver.resolve(connection, shard, query, scope);
            if (filter.isEmpty()) {
                return List.of();
            }
            Sql sql = shard.database().sql();
            List<EventRow> hot = eventRepository.find(connection, sql, filter.get(), cursor, limit, newestFirst);
            List<EventRow> archived = archive.find(connection, shard, filter.get(), cursor, limit, newestFirst, hot);
            List<EventRow> rows = new ArrayList<>(hot.size() + archived.size());
            rows.addAll(hot);
            rows.addAll(archived);
            Comparator<EventRow> order = Comparator.comparingLong(EventRow::id);
            rows.sort(newestFirst ? order.reversed() : order);
            return stored(connection, shard, rows.size() > limit ? rows.subList(0, limit) : rows);
        });
    }

    /**
     * Counts matching events, exactly.
     *
     * @param shard the shard
     * @param query the query
     * @param scope which of the shard's worlds
     * @return the count
     * @throws SQLException when reading fails
     */
    public long count(@NotNull Shard shard, @NotNull LookupQuery query, @NotNull ShardScope scope)
            throws SQLException {
        return count(shard, query, scope, Archive.ALL_SEGMENTS).total();
    }

    /**
     * Counts matching events: the hot tier exactly, the archive reading the bodies of at most
     * {@code maxSegments} segments, newest first ({@link Archive#count(Connection, Shard, EventFilter, int)}).
     *
     * @param shard       the shard
     * @param query       the query
     * @param scope       which of the shard's worlds
     * @param maxSegments the most archive segment bodies to read; {@link Archive#ALL_SEGMENTS} for all
     * @return the count, a lower bound when the archive budget ran out
     * @throws SQLException when reading fails
     */
    public @NotNull EventCount count(@NotNull Shard shard, @NotNull LookupQuery query, @NotNull ShardScope scope,
            int maxSegments) throws SQLException {
        return shard.database().read(connection -> {
            Optional<EventFilter> filter = queryResolver.resolve(connection, shard, query, scope);
            if (filter.isEmpty()) {
                return EventCount.of(0);
            }
            Sql sql = shard.database().sql();
            EventCount hot = EventCount.of(eventRepository.count(connection, sql, filter.get()));
            return hot.plus(archive.count(connection, shard, filter.get(), maxSegments));
        });
    }

    /**
     * Sets or clears the rolled-back flag of events, in one transaction, in whichever tier holds them.
     *
     * @param shard      the shard
     * @param ids        the event ids
     * @param rolledBack the new value
     * @return the number of events whose flag was set (hot events count whether or not they changed)
     * @throws SQLException when writing fails
     */
    public int setRolledBack(@NotNull Shard shard, @NotNull Collection<Long> ids, boolean rolledBack)
            throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        Sql sql = shard.database().sql();
        return shard.database().transaction(connection -> {
            int hot = eventRepository.setRolledBack(connection, sql, ids, rolledBack);
            return hot < ids.size() ? hot + archive.setRolledBack(connection, shard, ids, rolledBack) : hot;
        });
    }

    /**
     * Deletes one batch of events older than a time: up to {@code limit} hot events, and once the hot
     * tier has none left, up to {@link #PURGE_SEGMENTS} archive segments (or their older parts).
     *
     * @param shard        the shard
     * @param beforeMillis events strictly older than this are deleted
     * @param worlds       world ids to purge, or empty for every world
     * @param limit        the most hot events in this batch
     * @return the number of events deleted; 0 means the purge is complete
     * @throws SQLException when deleting fails
     */
    public int purgeBatch(@NotNull Shard shard, long beforeMillis, @NotNull Collection<Integer> worlds, int limit)
            throws SQLException {
        long belowId = EventIds.floor(beforeMillis);
        Sql sql = shard.database().sql();
        int hot = shard.database().read(connection -> eventRepository.deleteBatch(connection, sql, belowId, worlds,
                limit));
        if (hot >= limit) {
            return hot;
        }
        return hot + shard.database().transaction(connection -> archive.purge(connection, shard, belowId, worlds,
                PURGE_SEGMENTS));
    }

    /**
     * The worlds of some scopes in a shard (for purges by world).
     *
     * @param shard  the shard
     * @param scopes world scopes, or empty for all
     * @return world id to key
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, WorldKey> worlds(@NotNull Shard shard, @NotNull Collection<String> scopes)
            throws SQLException {
        return shard.database().read(connection -> shard.dictionaries().worlds(connection, shard.database().sql(),
                scopes));
    }

    /**
     * Every server registered in a shard.
     *
     * @param shard the shard
     * @return the servers by node id
     * @throws SQLException when reading fails
     */
    public @NotNull List<ServerNode> servers(@NotNull Shard shard) throws SQLException {
        return shard.database().read(connection -> shard.dictionaries().refreshServers(connection,
                shard.database().sql()));
    }

    /**
     * Interns the batch's dictionary values in auto-commit mode and builds its rows.
     *
     * @param rename whether a known player's stored name follows the batch ({@code false} for imports)
     */
    private static @NotNull List<EventRow> resolve(@NotNull Shard home, @NotNull ServerNode node,
            @NotNull String scope, @NotNull List<PendingEvent> events, boolean rename) throws SQLException {
        return home.database().read(connection -> rows(connection, home, node, scope, events, rename));
    }

    private static @NotNull List<EventRow> rows(@NotNull Connection connection, @NotNull Shard home,
            @NotNull ServerNode node, @NotNull String scope, @NotNull List<PendingEvent> events, boolean rename)
            throws SQLException {
        Sql sql = home.database().sql();
        Dictionaries dictionaries = home.dictionaries();
        Set<String> worldNames = new HashSet<>();
        Map<String, Actor> actors = new HashMap<>();
        Set<String> types = new LinkedHashSet<>();
        for (PendingEvent event : events) {
            worldNames.add(event.world());
            actors.putIfAbsent(event.actor().key(), event.actor());
            if (!event.subject().isEmpty()) {
                types.add(event.subject());
            }
            if (event.secondary() != null) {
                types.add(event.secondary());
            }
        }
        Map<String, Integer> worldIds = dictionaries.internWorlds(connection, sql, scope, worldNames);
        Map<String, Integer> actorIds = rename ? dictionaries.internActors(connection, sql, actors.values())
                : dictionaries.internActorsKeepingNames(connection, sql, actors.values());
        Map<String, Integer> typeIds = dictionaries.internTypes(connection, sql, types);
        List<EventRow> rows = new ArrayList<>(events.size());
        for (PendingEvent event : events) {
            rows.add(new EventRow(EventIds.fromStamp(event.stamp(), node.node()),
                    require(worldIds, event.world(), "world"),
                    event.position(),
                    require(actorIds, event.actor().key(), "actor"),
                    event.action().code(),
                    event.subject().isEmpty() ? 0 : require(typeIds, event.subject(), "type"),
                    event.secondary() == null ? 0 : require(typeIds, event.secondary(), "type"),
                    Math.max(0, event.amount()),
                    event.rolledBack() ? EventRow.ROLLED_BACK : 0,
                    event.data()));
        }
        return rows;
    }

    private static @NotNull List<StoredEvent> stored(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull List<EventRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        Sql sql = shard.database().sql();
        Dictionaries dictionaries = shard.dictionaries();
        Set<Integer> worldIds = new HashSet<>();
        Set<Integer> actorIds = new HashSet<>();
        Set<Integer> typeIds = new HashSet<>();
        Set<Integer> nodes = new HashSet<>();
        for (EventRow row : rows) {
            worldIds.add(row.world());
            actorIds.add(row.actor());
            nodes.add(EventIds.node(row.id()));
            if (row.subject() != 0) {
                typeIds.add(row.subject());
            }
            if (row.secondary() != 0) {
                typeIds.add(row.secondary());
            }
        }
        Map<Integer, WorldKey> worlds = dictionaries.worldKeys(connection, sql, worldIds);
        Map<Integer, Actor> actors = dictionaries.actors(connection, sql, actorIds);
        Map<Integer, String> types = dictionaries.typeNames(connection, sql, typeIds);
        Map<Integer, ServerNode> servers = dictionaries.servers(connection, sql, nodes);
        List<StoredEvent> out = new ArrayList<>(rows.size());
        for (EventRow row : rows) {
            Optional<Action> action = Action.byCode(row.action());
            if (action.isEmpty()) {
                continue;
            }
            WorldKey world = worlds.get(row.world());
            ServerNode server = servers.get(EventIds.node(row.id()));
            out.add(new StoredEvent(row.id(),
                    server == null ? "#" + EventIds.node(row.id()) : server.name(),
                    world == null ? "#" + row.world() : world.name(),
                    row.position(),
                    actors.getOrDefault(row.actor(), Actor.source("unknown")),
                    action.get(),
                    row.subject() == 0 ? "" : types.getOrDefault(row.subject(), "minecraft:air"),
                    row.secondary() == 0 ? null : types.getOrDefault(row.secondary(), "minecraft:air"),
                    row.amount(),
                    row.rolledBack(),
                    row.data()));
        }
        return out;
    }

    private static int require(@NotNull Map<String, Integer> ids, @NotNull String key, @NotNull String what)
            throws SQLException {
        Integer id = ids.get(key);
        if (id == null) {
            throw new SQLException("the " + what + " dictionary returned no id for " + key);
        }
        return id;
    }
}
