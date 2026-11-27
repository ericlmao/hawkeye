package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ExperimentalSettingsTest {

    @TempDir
    private Path folder;

    @Test
    public void everySwitchIsOffByDefault() throws IOException {
        assertFalse(new ExperimentalSettings().dialogLookup());

        HawkeyeConfig config = HawkeyeConfigProvider.readConfig(folder);

        assertFalse(config.experimental().dialogLookup());
        String file = written();
        assertTrue(file.contains("\nexperimental:\n"), file);
        assertTrue(file.contains("  dialog-lookup: false"), file);
    }

    @Test
    public void theDialogLookupCanBeTurnedOn() throws IOException {
        Files.writeString(configFile(), "experimental:\n  dialog-lookup: true\n");

        assertTrue(HawkeyeConfigProvider.readConfig(folder).experimental().dialogLookup());
        assertTrue(written().contains("  dialog-lookup: true"), written());
    }

    @Test
    public void aFileWithoutTheSectionLoadsAndGetsTheDefault() throws IOException {
        Files.writeString(configFile(), "lookup:\n  page-size: 10\n");

        HawkeyeConfig config = HawkeyeConfigProvider.readConfig(folder);

        assertFalse(config.experimental().dialogLookup());
        String file = written();
        assertTrue(file.contains("\nexperimental:\n") && file.contains("  dialog-lookup: false"), file);
        assertTrue(file.contains("  page-size: 10"), file);
    }

    private Path configFile() {
        return folder.resolve(HawkeyeConfigProvider.CONFIG_FILE);
    }

    private String written() throws IOException {
        return Files.readString(configFile()).replace("\r\n", "\n");
    }
}
