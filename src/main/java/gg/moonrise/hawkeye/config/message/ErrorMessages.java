package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code errors} section of {@code messages.yml}: {@code /hawkeye errors} and the notice on join. */
@Getter
@Configuration
public final class ErrorMessages {

    @Comment({"Told on join to players with hawkeye.errors while error or crash files wait to be reviewed",
            "(error-reports.join-notice). Placeholder: <reports> (the parts below)."})
    private String joinNotice = "<#7dd3fc>Hawkeye</#7dd3fc> <dark_gray>»</dark_gray> <reports> <gray>wait to be "
            + "reviewed:</gray> <click:run_command:'/hawkeye errors'><hover:show_text:'<gray>List them'><white><u>"
            + "/hawkeye errors</u></white></hover></click>";

    @Comment("<reports> part for crash files. Placeholder: <count>.")
    private String joinNoticeCrashes = "<red><count> crash report(s)</red>";

    @Comment("<reports> part for error files. Placeholder: <count>.")
    private String joinNoticeErrors = "<gold><count> error report(s)</gold>";

    @Comment("Between the two <reports> parts.")
    private String joinNoticeAnd = " <gray>and</gray> ";

    @Comment("First line of /hawkeye errors. Placeholder: <count>.")
    private String listHeader = "<#7dd3fc>----- Hawkeye reports waiting for review: <count> -----";

    @Comment({"One file in /hawkeye errors. Placeholders: <kind>, <folder> (errors or crash), <file>, <time> (how long",
            "ago it was last written), <size>, <problems> (how many problems it starts), <first> (the first one)."})
    private String listEntry = "<kind> <white><folder>/<file></white> <dark_gray>(<problems> problem(s), <size>, "
            + "<time> ago)</dark_gray><newline>  <gray><first>";

    @Comment("<kind> of a crash file.")
    private String kindCrash = "<red>crash</red>";

    @Comment("<kind> of an error file.")
    private String kindError = "<gold>error</gold>";

    @Comment("Last line of /hawkeye errors when files wait.")
    private String listFooter = "<gray>They are in plugins/Hawkeye/errors and crash. Once read: "
            + "<click:suggest_command:'/hawkeye errors reviewed'><white><u>/hawkeye errors reviewed</u></white>"
            + "</click> moves them to reviewed/.";

    @Comment("Reply to /hawkeye errors when nothing waits.")
    private String listEmpty = "<green>No error or crash report waits to be reviewed.";

    @Comment("Reply to /hawkeye errors reviewed. Placeholder: <count>.")
    private String reviewed = "<green>Marked <count> report(s) as reviewed: they are in plugins/Hawkeye/errors/"
            + "reviewed and crash/reviewed now.";

    @Comment("Reply when the report files cannot be read or moved. Placeholder: <reason>.")
    private String unavailable = "<red>The error and crash files could not be read: <reason>";
}
