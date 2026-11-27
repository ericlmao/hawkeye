package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/** The {@code dev-mode} section. */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class DevModeSettings {

    @Comment({"Turns on the /hawkeye dev commands (permission hawkeye.dev). /hawkeye dev generate <count> [days] writes",
            "that many fake events into this database: fake players, places, inventories and every kind of action.",
            "Fake data is stored like real history and stays until it is purged or the database is deleted."})
    private boolean enabled = false;
}
