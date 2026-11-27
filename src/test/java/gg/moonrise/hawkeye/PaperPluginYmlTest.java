package gg.moonrise.hawkeye;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.loader.HawkeyePluginLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

public class PaperPluginYmlTest {

    @Test
    public void declaresTheEntryPoints() throws IOException {
        Map<String, Object> yml = load();
        assertEquals(HawkeyePlugin.class.getName(), yml.get("main"));
        assertEquals(HawkeyePluginLoader.class.getName(), yml.get("loader"));
        // The oldest supported server: main compiles against its API (verifyPluginJar checks the two agree).
        assertEquals("1.21.1", yml.get("api-version"));
        assertEquals(true, yml.get("folia-supported"));
        assertFalse(String.valueOf(yml.get("version")).contains("${"), "version is expanded");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void declaresEveryCommandPermission() throws IOException {
        Map<String, Map<String, Object>> permissions = (Map<String, Map<String, Object>>) load().get("permissions");
        for (String node : List.of(Permissions.HELP, Permissions.INSPECT, Permissions.LOOKUP, Permissions.TELEPORT,
                Permissions.ROLLBACK, Permissions.PURGE, Permissions.IMPORT, Permissions.NETWORK, Permissions.ADMIN,
                Permissions.ERRORS, Permissions.DEV)) {
            Map<String, Object> permission = permissions.get(node);
            assertNotNull(permission, node);
            assertEquals("op", permission.get("default"), node);
            Map<String, Object> children = (Map<String, Object>) permissions.get("hawkeye.*").get("children");
            assertTrue(children.containsKey(node), node + " is a child of hawkeye.*");
        }
    }

    private static Map<String, Object> load() throws IOException {
        try (InputStream in = PaperPluginYmlTest.class.getClassLoader().getResourceAsStream("paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml is on the class path");
            return new Yaml().load(in);
        }
    }
}
