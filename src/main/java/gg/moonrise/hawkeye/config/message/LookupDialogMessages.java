package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/** The {@code lookup-dialog} section of {@code messages.yml}. */
@Getter
@Configuration
public final class LookupDialogMessages {

    @Comment("Title of the results dialog.")
    private String title = "<#7dd3fc>Hawkeye</#7dd3fc> <gray>results";

    @Comment({"First line of the results dialog. Placeholders: <page>, <pages>, <total>, <filters> (the parameters",
            "that found them, as typed text; empty when there are none)."})
    private String summary = "<gray>Page <white><page></white>/<white><pages></white>, <white><total></white> events "
            + "<dark_gray><filters>";

    @Comment("Button to the previous page. Placeholder: <page> (the page it opens).")
    private String previous = "<#7dd3fc>< Previous";

    @Comment("Button to the next page. Placeholder: <page> (the page it opens).")
    private String next = "<#7dd3fc>Next >";

    @Comment("Button that opens the filter form.")
    private String filters = "<#7dd3fc>Filters";

    @Comment("Button that closes a dialog.")
    private String close = "<gray>Close";

    @Comment("Title of the filter form.")
    private String filtersTitle = "<#7dd3fc>Hawkeye</#7dd3fc> <gray>filters";

    @Comment("Line above the filter form's fields.")
    private String hint = "<gray>Leave a field empty to not filter by it. Separate several values with commas or "
            + "spaces.";

    @Comment("Label of the players field.")
    private String players = "<gray>Players (u:)";

    @Comment("Label of the time field.")
    private String time = "<gray>Time (t:) e.g. 3d or 1h-3d";

    @Comment("Label of the radius field.")
    private String radius = "<gray>Radius (r:) e.g. 10, 10x5, #global";

    @Comment("Label of the actions field.")
    private String actions = "<gray>Actions (a:) e.g. -block, kill";

    @Comment("Label of the included types field.")
    private String include = "<gray>Only these types (i:)";

    @Comment("Label of the excluded types and actors field.")
    private String exclude = "<gray>Except these (e:)";

    @Comment("Label of the servers field (shown with hawkeye.network only).")
    private String servers = "<gray>Servers (s:)";

    @Comment("Label of the count-only checkbox.")
    private String countOnly = "<gray>Count only (#count)";

    @Comment("Button that runs the lookup with the form's fields.")
    private String search = "<#7dd3fc>Search";
}
