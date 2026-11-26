package gg.moonrise.hawkeye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.compat.ServerSoftware;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class ServerDetailsTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theSnapshotDescribesTheServer() {
        ServerSnapshot snapshot = new ServerDetails(plugin).snapshot();

        assertEquals(server.getMinecraftVersion(), snapshot.minecraftVersion());
        assertEquals(ServerSoftware.UNKNOWN.displayName(), snapshot.software(), "MockBukkit is no known server");
        assertEquals(server.getName(), snapshot.serverName());
        assertEquals(server.getVersion(), snapshot.serverVersion());
        assertTrue(snapshot.java().startsWith(Runtime.version().toString() + " ("), snapshot.java());
        assertTrue(snapshot.system().startsWith(System.getProperty("os.name")), snapshot.system());
        assertEquals(plugin.getPluginMeta().getVersion(), snapshot.hawkeyeVersion());
    }

    @Test
    public void thePluginListIsReadEachTimeAndSortedByName() {
        Plugin other = MockBukkit.createMockPlugin("anotherPlugin");
        ServerDetails details = new ServerDetails(plugin);
        assertTrue(details.snapshot().plugins().contains(new ServerSnapshot.PluginLine("anotherPlugin",
                other.getPluginMeta().getVersion(), true)));

        server.getPluginManager().disablePlugin(other);

        var plugins = details.snapshot().plugins();
        assertTrue(plugins.contains(new ServerSnapshot.PluginLine("anotherPlugin", other.getPluginMeta().getVersion(),
                false)), plugins.toString());
        assertTrue(plugins.contains(new ServerSnapshot.PluginLine(plugin.getName(), plugin.getPluginMeta().getVersion(),
                true)), plugins.toString());
        assertEquals("anotherPlugin", plugins.getFirst().name(), "sorted ignoring case: " + plugins);
    }
}
