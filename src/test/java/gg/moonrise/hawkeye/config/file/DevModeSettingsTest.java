package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DevModeSettingsTest {

    @TempDir
    private Path folder;

    @Test
    public void devModeIsOffByDefaultAndSaysWhatItIsFor() throws IOException {
        assertFalse(new DevModeSettings().enabled());

        HawkeyeConfig config = HawkeyeConfigProvider.readConfig(folder);

        assertFalse(config.devMode().enabled());
        String file = written();
        int section = file.indexOf("\ndev-mode:\n");
        assertTrue(section > 0, file);
        assertTrue(file.indexOf("  enabled: false", section) > section, file);
        assertTrue(file.contains("not meant to be used on any"), file);
        assertTrue(file.contains("Dev mode is made for developing Hawkeye itself"), file);
    }

    @Test
    public void devModeCanBeTurnedOn() throws IOException {
        Files.writeString(configFile(), "dev-mode:\n  enabled: true\n");

        assertTrue(HawkeyeConfigProvider.readConfig(folder).devMode().enabled());
    }

    @Test
    public void aFileWithoutTheSectionLoadsAndGetsTheDefault() throws IOException {
        Files.writeString(configFile(), "lookup:\n  page-size: 10\n");

        HawkeyeConfig config = HawkeyeConfigProvider.readConfig(folder);

        assertFalse(config.devMode().enabled());
        String file = written();
        assertTrue(file.contains("\ndev-mode:\n"), file);
        assertTrue(file.contains("  page-size: 10"), file);
    }

    private Path configFile() {
        return folder.resolve(HawkeyeConfigProvider.CONFIG_FILE);
    }

    private String written() throws IOException {
        return Files.readString(configFile()).replace("\r\n", "\n");
    }
}
