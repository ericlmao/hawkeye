package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * The names and text of the error and crash files. A file is named after the minute its first problem was logged
 * ({@code errors/Error-2026-11-25-14-03.txt}); problems first logged in the same minute share it, and every repeat
 * of a problem goes into the file of its first time. Each run writes the server header once per file before its
 * first entry, so a file reused after a restart in the same minute describes both runs.
 */
public final class ReportFormat {

    /** The date and time in a file name: year-month-day-hour-minute. */
    public static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm", Locale.ROOT);

    /** With the UTC offset, not the zone's name: JDKs name zones differently (Java 21 "CST", 25 "GMT-06:00"). */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx",
            Locale.ROOT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final String RULE = "=".repeat(78);

    private final @NotNull ZoneId zone;

    /**
     * A format for one time zone (the server's).
     *
     * @param zone the zone of file names and times
     */
    public ReportFormat(@NotNull ZoneId zone) {
        this.zone = zone;
    }

    /**
     * The file a problem first logged at a time is written to.
     *
     * @param dataFolder Hawkeye's data folder
     * @param kind       where the problem is filed
     * @param timeMillis when it was logged
     * @return {@code <dataFolder>/errors/Error-2026-11-25-14-03.txt}
     */
    public @NotNull Path file(@NotNull Path dataFolder, @NotNull ProblemKind kind, long timeMillis) {
        return dataFolder.resolve(kind.folder()).resolve(kind.filePrefix() + "-" + FILE_TIME.format(at(timeMillis))
                + ".txt");
    }

    /**
     * The server header that opens a file (or this run's part of a reused one).
     *
     * @param kind       what the file holds
     * @param server     the server facts
     * @param timeMillis when the file was started
     * @return the header text, ending with a blank line
     */
    public @NotNull String header(@NotNull ProblemKind kind, @NotNull ServerSnapshot server, long timeMillis) {
        StringBuilder text = new StringBuilder();
        text.append("Hawkeye ").append(kind.title()).append(", started ").append(STAMP.format(at(timeMillis)))
                .append(System.lineSeparator()).append(System.lineSeparator());
        line(text, "Server");
        line(text, "  Minecraft version: " + server.minecraftVersion());
        line(text, "  Server type: " + server.software());
        line(text, "  Server: " + server.serverName() + " " + server.serverVersion());
        line(text, "  Java: " + server.java());
        line(text, "  Operating system: " + server.system());
        line(text, "  Hawkeye: " + server.hawkeyeVersion());
        text.append(System.lineSeparator());
        line(text, "Plugins (" + server.plugins().size() + ")");
        for (ServerSnapshot.PluginLine plugin : server.plugins()) {
            line(text, "  " + plugin.name() + " " + plugin.version() + (plugin.enabled() ? "" : " (not enabled)"));
        }
        text.append(System.lineSeparator());
        return text.toString();
    }

    /**
     * A problem's first entry: when, where, the message and the whole stack trace.
     *
     * @param number  its number in this run
     * @param problem the problem
     * @return the entry, ending with a blank line
     */
    public @NotNull String entry(int number, @NotNull Problem problem) {
        StringBuilder text = new StringBuilder();
        line(text, RULE);
        line(text, "#" + number + " " + problem.level() + " at " + STAMP.format(at(problem.timeMillis())));
        line(text, "Logger: " + problem.logger());
        line(text, "Thread: " + problem.thread());
        line(text, problem.message());
        Throwable thrown = problem.thrown();
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            try (PrintWriter writer = new PrintWriter(trace)) {
                thrown.printStackTrace(writer);
            }
            text.append(trace);
        }
        text.append(System.lineSeparator());
        return text.toString();
    }

    /**
     * One more time a problem was logged.
     *
     * @param number     its number in this run
     * @param occurrence how often it has been logged now
     * @param problem    this time's problem
     * @return the line
     */
    public @NotNull String repeat(int number, long occurrence, @NotNull Problem problem) {
        return "[" + STAMP.format(at(problem.timeMillis())) + "] #" + number + " again (" + occurrence + " times): "
                + problem.headline() + System.lineSeparator();
    }

    /**
     * Repeats counted instead of written one by one, once a problem has been written {@link ErrorLog#REPEAT_LINES}
     * times.
     *
     * @param number    its number in this run
     * @param count     how many repeats this line stands for
     * @param fromMillis the first of them
     * @param toMillis   the last of them
     * @param total     how often it has been logged now
     * @param last      the last of them
     * @return the line
     */
    public @NotNull String summary(int number, long count, long fromMillis, long toMillis, long total,
                                   @NotNull Problem last) {
        return "[" + STAMP.format(at(toMillis)) + "] #" + number + " again " + count + " more times from "
                + CLOCK.format(at(fromMillis)) + " to " + CLOCK.format(at(toMillis)) + " (" + total
                + " times); the last: " + last.headline() + System.lineSeparator();
    }

    /**
     * Problems that were not written because the error log fell behind.
     *
     * @param count      how many
     * @param timeMillis when this was noticed
     * @return the line
     */
    public @NotNull String dropped(long count, long timeMillis) {
        return "[" + STAMP.format(at(timeMillis)) + "] " + count
                + " logged problem(s) were not written: the error log could not keep up" + System.lineSeparator();
    }

    /**
     * How the console and the webhook name a file: relative to the server folder with {@code /} separators
     * ({@code plugins/Hawkeye/errors/Error-...txt}), or absolute when it is elsewhere.
     *
     * @param serverFolder the server's folder, absolute and normalized
     * @param file         the file
     * @return the name to show
     */
    public static @NotNull String shownPath(@NotNull Path serverFolder, @NotNull Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path shown = absolute.startsWith(serverFolder) ? serverFolder.relativize(absolute) : absolute;
        return shown.toString().replace('\\', '/');
    }

    private @NotNull ZonedDateTime at(long timeMillis) {
        return Instant.ofEpochMilli(timeMillis).atZone(zone);
    }

    private static void line(@NotNull StringBuilder text, @NotNull String line) {
        text.append(line).append(System.lineSeparator());
    }
}
