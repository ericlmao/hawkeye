package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code dev} section of {@code messages.yml}: {@code /hawkeye dev} (dev mode). */
@Getter
@Configuration
public final class DevMessages {

    @Comment("Reply to every /hawkeye dev command while dev-mode.enabled is false in config.yml.")
    private String disabled = "<red>Dev mode is off. It is for developing Hawkeye, never for a real server: set "
            + "<white>dev-mode.enabled</white> in config.yml and run <white>/he reload</white>.";

    @Comment("Reply when generating fake data begins. Placeholders: <count>, <days>, <players>, <node>.")
    private String started = "<gray>Generating <white><count></white> fake event(s) over the last "
            + "<white><days></white> day(s) as <white><node></white>. Use <white>/he dev status</white> to follow it.";

    @Comment({"Sent every 10 seconds while fake data is generated. Placeholders: <events> (written so far), <count>",
            "(asked for), <percent>, <rate> (events per second), <duration> (so far), <remaining> (an estimate of the",
            "time left), <players>, <days>, <node>."})
    private String progress = "<gray>Generating: <white><events></white>/<white><count></white> event(s) "
            + "(<white><percent>%</white>), <white><rate></white> events/s, about <white><remaining></white> left.";

    @Comment("Reply to /hawkeye dev status while fake data is generated. Same placeholders as progress.")
    private String status = "<gray>Generating fake data as <white><node></white> for <white><duration></white>: "
            + "<white><events></white>/<white><count></white> event(s) (<white><percent>%</white>), "
            + "<white><rate></white> events/s, about <white><remaining></white> left.";

    @Comment("<remaining> before the first events are written.")
    private String remainingUnknown = "?";

    @Comment({"Reply when every fake event is written. Placeholders: <events>, <count>, <players>, <days>, <node>,",
            "<duration>, <rate>."})
    private String finished = "<green>Fake data done:</green> <gray><white><events></white> event(s) by "
            + "<white><players></white> fake player(s) written as <white><node></white> in <duration> "
            + "(<rate> events/s).";

    @Comment({"Reply when generating stopped early (/hawkeye dev cancel, or dev mode turned off). Placeholders:",
            "<events>, <count>, <node>, <duration>."})
    private String cancelled = "<yellow>Generating stopped after <white><events></white> of <white><count></white> "
            + "fake event(s).</yellow> <gray>What was written stays.";

    @Comment("Reply to /hawkeye dev cancel while fake data is generated.")
    private String cancelling = "<gray>Stopping after the current batch...";

    @Comment("Reply when generating is started while it already runs.")
    private String alreadyRunning = "<red>Fake data is already being generated; see <white>/he dev status</white>.";

    @Comment("Reply to /hawkeye dev status and cancel when nothing is generated.")
    private String notRunning = "<gray>No fake data is being generated.";

    @Comment("Reply to /hawkeye dev generate with a count below 1.")
    private String invalidCount = "<red>The number of events must be at least 1.";

    @Comment("Reply to /hawkeye dev generate with fewer than 1 day.")
    private String invalidDays = "<red>The number of days must be at least 1.";

    @Comment("Reply when the database has no server id left for another run (each run takes one of 1023).")
    private String noNodeLeft = "<red>This database has no server id left for more fake data (each run takes one of "
            + "1023). Start again with a fresh database.";
}
