package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/** The {@code experimental} section. */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class ExperimentalSettings {

    @Comment({"Players get /hawkeye lookup and /hawkeye near as a dialog instead of chat lines. Needs Paper 1.21.7 or",
            "newer and a 1.21.6 or newer client; the console, and everyone on an older server, keeps the chat output."})
    private boolean dialogLookup = false;
}
