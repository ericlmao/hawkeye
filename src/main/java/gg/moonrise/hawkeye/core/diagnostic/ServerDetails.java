package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.util.compat.ServerSoftware;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the server facts for {@link ServerSnapshot}. The fixed ones are read once, when Hawkeye loads; the plugin
 * list each time a file is started, from the error log's own thread (the list is a copy, and a plugin loaded
 * meanwhile only makes it a little stale).
 */
public final class ServerDetails {

    private final @NotNull Server server;
    private final @NotNull String minecraftVersion;
    private final @NotNull String software;
    private final @NotNull String serverName;
    private final @NotNull String serverVersion;
    private final @NotNull String java;
    private final @NotNull String system;
    private final @NotNull String hawkeyeVersion;

    /**
     * Reads the fixed facts of a plugin's server.
     *
     * @param plugin Hawkeye
     */
    public ServerDetails(@NotNull Plugin plugin) {
        this.server = plugin.getServer();
        this.minecraftVersion = server.getMinecraftVersion();
        this.software = ServerSoftware.detect(server.getClass().getClassLoader()).displayName();
        this.serverName = server.getName();
        this.serverVersion = server.getVersion();
        this.java = Runtime.version() + " (" + property("java.vendor") + ", " + property("java.vm.name") + ")";
        this.system = property("os.name") + " " + property("os.version") + " (" + property("os.arch") + ")";
        this.hawkeyeVersion = plugin.getPluginMeta().getVersion();
    }

    /**
     * The facts now, with the plugins currently loaded.
     *
     * @return the snapshot
     */
    public @NotNull ServerSnapshot snapshot() {
        return new ServerSnapshot(minecraftVersion, software, serverName, serverVersion, java, system,
                hawkeyeVersion, plugins());
    }

    private @NotNull List<ServerSnapshot.PluginLine> plugins() {
        List<ServerSnapshot.PluginLine> lines = new ArrayList<>();
        try {
            for (Plugin plugin : server.getPluginManager().getPlugins()) {
                if (plugin != null) {
                    lines.add(new ServerSnapshot.PluginLine(plugin.getName(), plugin.getPluginMeta().getVersion(),
                            plugin.isEnabled()));
                }
            }
        } catch (RuntimeException e) {
            // A plugin list changing under a read off the main thread: what was read is still worth showing.
        }
        lines.sort(Comparator.comparing(ServerSnapshot.PluginLine::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(lines);
    }

    private static @NotNull String property(@NotNull String key) {
        return Objects.requireNonNullElse(System.getProperty(key), "?");
    }
}
