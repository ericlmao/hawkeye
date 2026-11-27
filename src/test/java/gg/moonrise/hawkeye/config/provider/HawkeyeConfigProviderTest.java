package gg.moonrise.hawkeye.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.config.file.StorageLogin;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.config.file.StorageType;
import gg.moonrise.hawkeye.core.diagnostic.ConsoleLines;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class HawkeyeConfigProviderTest {

    private TestHawkeyePlugin plugin;
    private HawkeyeConfigProvider provider;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        provider = TestPlugins.bean(HawkeyeConfigProvider.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void writesEveryKeyInKebabCase() throws IOException {
        String config = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE));
        assertTrue(config.contains("flush-interval-millis: 500"), config);
        assertTrue(config.contains("table-prefix: hawkeye_"), config);
        assertTrue(config.contains("redacted-commands:"), config);
        assertTrue(config.contains("\ndev-mode:\n"), config);
        String messages = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.MESSAGES_FILE));
        assertTrue(messages.contains("block-break:"), messages);
        assertTrue(messages.contains("storage-not-ready:"), messages);
        assertTrue(messages.contains("lookup-dialog:"), messages);
        assertTrue(messages.contains("  filters-title:"), messages);
        assertTrue(messages.contains("  count-only:"), messages);
        String commands = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.COMMANDS_FILE));
        assertTrue(commands.contains("root-aliases:"), commands);
        assertTrue(commands.contains("subcommand-aliases:"), commands);
        assertTrue(commands.contains("lookup:"), commands);
    }

    @Test
    public void reloadAppliesAValidFileAndKeepsDefaultsForMissingKeys() {
        TestPlugins.writeConfig(plugin, "logging:\n  chat: false\nstorage:\n  type: POSTGRESQL\n  host: db\n"
                + "  username: hawkeye\n  password: literal\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertFalse(provider.get().logging().enabled(LogCategory.CHAT));
        assertTrue(provider.get().logging().enabled(LogCategory.BLOCK_BREAK), "missing keys get their defaults");
        assertEquals(StorageType.POSTGRESQL, provider.get().storage().type());
        assertEquals(5432, provider.get().storage().effectivePort());
        assertEquals(new HawkeyeConfig().writer(), provider.get().writer());
    }

    @Test
    public void anInvalidFileKeepsThePreviousSnapshot() {
        HawkeyeConfig before = provider.get();
        TestPlugins.writeConfig(plugin, "writer:\n  batch-size: 0\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("writer.batch-size"), failed.reason());
        assertSame(before, provider.get());
    }

    @Test
    public void aBrokenFileKeepsThePreviousSnapshot() {
        HawkeyeConfig before = provider.get();
        TestPlugins.writeConfig(plugin, "writer: [not, a, section]\n");
        assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertSame(before, provider.get());
    }

    @Test
    public void aFileWithTheRemovedBatchEventsKeyStillLoads() throws IOException {
        TestPlugins.writeConfig(plugin, "archive:\n  enabled: true\n  after-hours: 12\n  interval-minutes: 30\n"
                + "  batch-events: 50000\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertEquals(12, provider.get().archive().afterHours());
        assertEquals(30, provider.get().archive().intervalMinutes());
        assertEquals(262_144, provider.get().archive().segmentEvents(), "a key missing from the file gets its default");
        assertEquals(60, provider.get().archive().settleSeconds());
        // The reload writes the file back with the current keys: the removed one is dropped, the new ones added.
        String written = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE));
        assertFalse(written.contains("batch-events"), written);
        assertTrue(written.contains("segment-events: 262144") && written.contains("settle-seconds: 60"), written);
        assertTrue(written.contains("after-hours: 12"), written);
    }

    @Test
    public void aMessagesFileWithoutTheLowerBoundKeyGetsItsDefault() throws IOException {
        Path messages = plugin.getDataPath().resolve(HawkeyeConfigProvider.MESSAGES_FILE);
        Files.writeString(messages, "lookup:\n  unknown-total: '<gray>?'\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertEquals("<gray>?", provider.messages().getLookup().getUnknownTotal());
        assertEquals("<value>+", provider.messages().getLookup().getAtLeast(), "a key missing from the file");
        String written = Files.readString(messages);
        assertTrue(written.contains("at-least: <value>+") || written.contains("at-least: '<value>+'"), written);
        assertTrue(written.contains("<gray>?"), written);
    }

    @Test
    public void archiveSettingsAreValidated() {
        TestPlugins.writeConfig(plugin, "archive:\n  segment-events: 1000\n  settle-seconds: 2\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("archive.segment-events"), failed.reason());
        assertTrue(failed.reason().contains("archive.settle-seconds"), failed.reason());
    }

    @Test
    public void theExperimentalSwitchesApplyOnReload() {
        assertFalse(provider.get().experimental().dialogLookup());
        TestPlugins.writeConfig(plugin, "experimental:\n  dialog-lookup: true\n");

        ReloadResult.Reloaded reloaded = assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());

        assertEquals(List.of(), reloaded.restartRequired());
        assertTrue(provider.get().experimental().dialogLookup());
    }

    @Test
    public void devModeAppliesOnReload() {
        assertFalse(provider.get().devMode().enabled());
        TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: true\n");

        ReloadResult.Reloaded reloaded = assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());

        assertEquals(List.of(), reloaded.restartRequired());
        assertTrue(provider.get().devMode().enabled());
    }

    @Test
    public void turningDevModeOnIsAnnouncedOnceAtInfo() {
        String name = HawkeyeConfigProvider.class.getName();
        Level before = LogManager.getLogger(name).getLevel();
        Configurator.setLevel(name, Level.INFO);
        try (ConsoleLines console = ConsoleLines.attach()) {
            TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: true\n");
            assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
            assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());

            List<LogEvent> announced = console.of(name).stream()
                    .filter(event -> event.getMessage().getFormattedMessage().contains("Dev mode is on")).toList();
            assertEquals(1, announced.size(), announced.toString());
            assertEquals(Level.INFO, announced.getFirst().getLevel());

            TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: false\n");
            assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
            TestPlugins.writeConfig(plugin, "dev-mode:\n  enabled: true\n");
            assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
            assertEquals(2, console.of(name).stream()
                    .filter(event -> event.getMessage().getFormattedMessage().contains("Dev mode is on")).count(),
                    "announced again after it was off");
        } finally {
            Configurator.setLevel(name, before);
        }
    }

    @Test
    public void defaultsAreValid() {
        assertEquals(List.of(), new HawkeyeConfig().problems(name -> Optional.empty()));
    }

    @Test
    public void theDefaultFileReadsTheCredentialsFromTheEnvironmentAndSurvivesAReload() throws IOException {
        Path file = plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE);
        String written = Files.readString(file);
        // SnakeYAML quotes a value with braces.
        assertTrue(written.contains("username: '${HAWKEYE_DB_USERNAME}'"), written);
        assertTrue(written.contains("password: '${HAWKEYE_DB_PASSWORD}'"), written);
        assertTrue(written.contains("redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379"), "the comment's example");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertEquals(written, Files.readString(file), "a reload writes the references back unchanged");
        assertEquals(new StorageSettings(), provider.get().storage());
    }

    @Test
    public void anUnsetVariableFailsTheReloadByName() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: POSTGRESQL\n  host: db\n");
        HawkeyeConfigProvider bare = new HawkeyeConfigProvider(plugin, name -> Optional.empty());
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, bare.reloadConfig());
        assertTrue(failed.reason().contains("storage.username: the environment variable HAWKEYE_DB_USERNAME is not set"),
                failed.reason());
        assertTrue(failed.reason().contains("storage.password: the environment variable HAWKEYE_DB_PASSWORD is not set"),
                failed.reason());

        Environment set = name -> Optional.ofNullable(Map.of(StorageSettings.USERNAME_VARIABLE, "alex",
                StorageSettings.PASSWORD_VARIABLE, "s3cr3t").get(name));
        HawkeyeConfigProvider configured = new HawkeyeConfigProvider(plugin, set);
        assertInstanceOf(ReloadResult.Reloaded.class, configured.reloadConfig());
        StorageLogin login = configured.get().storage().login(set);
        assertEquals("alex", login.username());
        assertEquals("s3cr3t", login.password());
    }

    @Test
    public void aYamlErrorOnThePasswordLineDoesNotShowIt() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: POSTGRESQL\n  password: \"hunter2-secret\n"
                + "  pool-size: 6\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertFalse(failed.reason().contains("hunter2"), failed.reason());
        assertTrue(failed.reason().contains("YAML"), failed.reason());
    }

    @Test
    public void aNumericPasswordIsText() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: POSTGRESQL\n  username: 4711\n  password: 123456\n"
                + "  parameters:\n    connectTimeout: 5\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        StorageLogin login = provider.get().storage().login(name -> Optional.empty());
        assertEquals("4711", login.username());
        assertEquals("123456", login.password());
        assertEquals(Map.of("connectTimeout", "5"), login.parameters());
    }

    @Test
    public void aWronglyTypedCredentialIsNamedNotQuoted() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: POSTGRESQL\n  password: [alpha-secret, beta-secret]\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("storage.password"), failed.reason());
        assertFalse(failed.reason().contains("secret"), failed.reason());

        TestPlugins.writeConfig(plugin, "network:\n  redis-uri: {host: hunter2-secret}\n");
        failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("network.redis-uri"), failed.reason());
        assertFalse(failed.reason().contains("hunter2"), failed.reason());
    }

    @Test
    public void aWrongTypeNextToACredentialDoesNotShowTheSection() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: POSTGRESQL\n  username: alexander\n"
                + "  password: hunter2-secret\n  pool-size: many\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("storage.pool-size"), failed.reason());
        assertFalse(failed.reason().contains("hunter2") || failed.reason().contains("alexander"), failed.reason());

        TestPlugins.writeConfig(plugin, "network:\n  shards:\n  - name: eu\n    storage:\n"
                + "      password: shard-secret\n  - 5\n");
        failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("network.shards"), failed.reason());
        assertFalse(failed.reason().contains("shard-secret"), failed.reason());
    }

    @Test
    public void otherErrorsKeepTheirDetail() {
        TestPlugins.writeConfig(plugin, "storage:\n  type: ORACLE\n  password: hunter2-secret\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("storage.type") && failed.reason().contains("ORACLE")
                && failed.reason().contains("POSTGRESQL"), failed.reason());
        assertFalse(failed.reason().contains("hunter2"), failed.reason());
    }
}
