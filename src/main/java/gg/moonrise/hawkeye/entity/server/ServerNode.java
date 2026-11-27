package gg.moonrise.hawkeye.entity.server;

import org.jetbrains.annotations.NotNull;

/**
 * A server registered in a shard. A row whose {@code lastSeen} is 0 is an <em>import node</em>: the node
 * id of history imported from another plugin, or of one run of fake data generated in dev mode, never a
 * running server ({@link #imported()}).
 *
 * @param node     its node id (1 to 1023), the middle bits of every event id it writes
 * @param name     its configured name
 * @param scope    its world scope
 * @param lastSeen its last heartbeat, Unix milliseconds; 0 for an import node
 */
public record ServerNode(int node, @NotNull String name, @NotNull String scope, long lastSeen) {

    /**
     * Whether this is an import node: it only numbers imported events (or one dev mode run's generated
     * ones) and never runs, so it is left out of the network view and of network job destinations.
     *
     * @return {@code true} when {@code lastSeen} is 0
     */
    public boolean imported() {
        return lastSeen == 0;
    }
}
