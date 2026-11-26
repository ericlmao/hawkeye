package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.List;
import lombok.Getter;

/** The {@code general} section of {@code messages.yml}. */
@Getter
@Configuration
public final class GeneralMessages {

    @Comment("Reply to /hawkeye. Placeholders: <name>, <version>.")
    private String info = "<#7dd3fc>Hawkeye</#7dd3fc> <gray><version></gray> <dark_gray>-</dark_gray> "
            + "<gray>/hawkeye help</gray>";

    @Comment("Reply to /hawkeye help, one entry per line.")
    private List<String> help = List.of(
            "<#7dd3fc><bold>Hawkeye</bold></#7dd3fc> <dark_gray>-</dark_gray> <gray>parameters: u: t: r: a: i: e: s:"
                    + "</gray>",
            "<white>/he inspect</white> <dark_gray>-</dark_gray> <gray>toggle the inspector</gray>",
            "<white>/he lookup <params></white> <dark_gray>-</dark_gray> <gray>search the history</gray>",
            "<white>/he near [radius]</white> <dark_gray>-</dark_gray> <gray>what happened around you</gray>",
            "<white>/he rollback <params></white> <dark_gray>-</dark_gray> <gray>undo changes (#preview to "
                    + "preview)</gray>",
            "<white>/he restore <params></white> <dark_gray>-</dark_gray> <gray>redo rolled-back changes</gray>",
            "<white>/he undo</white> <dark_gray>-</dark_gray> <gray>revert your last rollback or restore</gray>",
            "<white>/he purge t:<age></white> <dark_gray>-</dark_gray> <gray>delete old data</gray>",
            "<white>/he import coreprotect [source]</white> <dark_gray>-</dark_gray> <gray>import CoreProtect "
                    + "history</gray>",
            "<white>/he status</white> <dark_gray>-</dark_gray> <gray>queue, storage and network health</gray>",
            "<white>/he errors</white> <dark_gray>-</dark_gray> <gray>error and crash reports to review</gray>");

    @Comment("Reply to /hawkeye reload when every file was read.")
    private String reloaded = "<green>Hawkeye configuration reloaded.";

    @Comment("Reply to /hawkeye reload when a file is broken. Placeholder: <reason>.")
    private String reloadFailed = "<red>The configuration could not be reloaded and the previous one stays active: "
            + "<reason>";

    @Comment("Reply to /hawkeye reload when a changed setting only applies after a restart. Placeholder: <settings>.")
    private String reloadRestartRequired = "<yellow>Restart the server to apply: <settings>";

    @Comment("Reply while the database is still connecting or is unreachable.")
    private String storageNotReady = "<red>Hawkeye's database is not ready yet; try again in a moment.";

    @Comment("Reply when a command that needs a player is run from the console.")
    private String playersOnly = "<red>Only players can do that.";

    @Comment("Reply when a parameter list cannot be read. Placeholder: <reason>.")
    private String invalidParameters = "<red><reason>";

    @Comment("Reply when a database operation fails. Placeholder: <reason>.")
    private String operationFailed = "<red>That failed: <reason>";

    @Comment("Reply when a command needs a time parameter. Placeholder: <command>.")
    private String timeRequired = "<red>Add a time, e.g. <white>t:2h</white>.";

    @Comment("Reply when a radius is above the limit. Placeholder: <max>.")
    private String radiusTooLarge = "<red>The largest radius you may use is <max>.";

    @Comment("Reply to /hawkeye tp. Placeholders: <world>, <x>, <y>, <z>.")
    private String teleported = "<gray>Teleported to <white><x> <y> <z></white> in <white><world></white>.";

    @Comment("Reply to /hawkeye tp for an unknown world. Placeholder: <world>.")
    private String unknownWorld = "<red>There is no world called <world> on this server.";
}
