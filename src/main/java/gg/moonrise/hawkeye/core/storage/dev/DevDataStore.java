package gg.moonrise.hawkeye.core.storage.dev;

import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The storage of fake data generated in dev mode, on the home shard: one fresh node per run (a
 * {@code server} row with {@code last_seen = 0}, like an import node) and the write of one batch straight into
 * the archive. A fresh node per run keeps every id distinct: ranks restart at 0 in every second of every node,
 * so two runs over the same seconds under one node would issue the same ids.
 */
@SpringComponent
@RequiredArgsConstructor
public final class DevDataStore {

    /** What a dev node's name adds to the server name: {@code survival-dev}, then {@code survival-dev-2}, ... */
    public static final String NODE_SUFFIX = "-dev";

    private final @NotNull ServerRepository serverRepository;
    private final @NotNull EventStore eventStore;

    /**
     * Registers the node of a new run: the first free name of {@code <serverName>-dev}, {@code -dev-2}, ...
     *
     * @param home       the home shard
     * @param serverName this server's name
     * @param scope      the world scope the fake events are stored under
     * @return the new node, or empty when the shard has no node id left (nothing is registered then)
     * @throws SQLException when reading or writing fails
     */
    public @NotNull Optional<ServerNode> registerNode(@NotNull Shard home, @NotNull String serverName,
            @NotNull String scope) throws SQLException {
        List<ServerNode> servers = eventStore.servers(home);
        if (servers.stream().anyMatch(server -> server.node() >= EventIds.MAX_NODE)) {
            return Optional.empty();
        }
        Set<String> taken = servers.stream().map(ServerNode::name).collect(Collectors.toSet());
        Database database = home.database();
        for (int attempt = 1; attempt <= EventIds.MAX_NODE + 1; attempt++) {
            String name = serverName + NODE_SUFFIX + (attempt == 1 ? "" : "-" + attempt);
            if (taken.contains(name)) {
                continue;
            }
            Optional<ServerNode> node = database.read(connection -> serverRepository.registerImport(connection,
                    database.sql(), name, scope));
            if (node.isPresent()) {
                return node;
            }
            // Another server took the name between the listing and the insert.
        }
        throw new SQLException("no free dev node name for " + serverName);
    }

    /**
     * Writes one batch of fake events as one archive segment, in one transaction
     * ({@link EventStore#writeArchived}). It is not idempotent, so the caller never retries a failed batch;
     * the events need distinct stamps and must be older than {@code archive.settle-seconds}, like everything
     * the archive holds.
     *
     * @param home     the home shard
     * @param node     the run's node
     * @param scope    the world scope the events are stored under
     * @param events   the events, with final stamps
     * @param executor where the segment's frames are compressed
     * @return the number of events written
     * @throws SQLException when interning or writing fails
     */
    public int write(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events, @NotNull Executor executor) throws SQLException {
        return eventStore.writeArchived(home, node, scope, events, executor);
    }
}
