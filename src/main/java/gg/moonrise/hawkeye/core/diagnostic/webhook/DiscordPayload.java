package gg.moonrise.hawkeye.core.diagnostic.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.moonrise.hawkeye.core.diagnostic.WrittenReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.text.TextCuts;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The body of one Discord webhook message for a {@link WrittenReport}: {@code multipart/form-data} with
 * {@code payload_json} (one embed: kind and server in the title, the one-line message in a code block, the file
 * and the server in fields) and the report file as {@code files[0]}. Mentions are switched off, so text from the
 * game ({@code @everyone} in a player's input) pings nobody, and every backtick in the message is followed by a
 * zero-width space so the message cannot close its code block. Embed limits are Discord's: title 256, description
 * 4096, field value 1024 characters.
 */
public final class DiscordPayload {

    /**
     * Files larger than this are not attached: a report is a few kilobytes, and a queued post holds its file in
     * memory until it is sent.
     */
    public static final long MAX_ATTACHMENT = 1024L * 1024;

    private static final int CRASH_COLOR = 0xE74C3C;
    private static final int ERROR_COLOR = 0xE67E22;
    private static final int WARNING_COLOR = 0xF1C40F;
    private static final int MAX_TITLE = 256;
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_FIELD = 1024;
    private static final String FENCE = "```";

    private DiscordPayload() {
    }

    /**
     * The JSON part.
     *
     * @param report     the problem
     * @param serverName this server's name ({@code server.name})
     * @param server     the server software and version ({@code Paper 26.3-262 (MC: 26.3)})
     * @param shownPath  the file as the console names it ({@code plugins/Hawkeye/errors/Error-...txt})
     * @param attached   whether the file goes along
     * @param dropped    reports not sent before this one (the queue was full), 0 for none
     * @return the {@code payload_json} object
     */
    public static @NotNull JsonObject json(@NotNull WrittenReport report, @NotNull String serverName,
                                           @NotNull String server, @NotNull String shownPath, boolean attached,
                                           long dropped) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", cut(what(report) + " on " + serverName, MAX_TITLE));
        embed.addProperty("description", FENCE + "\n" + cut(report.headline().replace("`", "`\u200b"),
                MAX_DESCRIPTION - 2 * FENCE.length() - 2) + "\n" + FENCE);
        embed.addProperty("color", color(report));
        embed.addProperty("timestamp", Instant.ofEpochMilli(report.timeMillis()).toString());
        JsonArray fields = new JsonArray();
        fields.add(field("File", cut(shownPath + (attached ? "" : " (too large to attach)"), MAX_FIELD)));
        fields.add(field("Server", cut(server, MAX_FIELD)));
        if (dropped > 0) {
            fields.add(field("Not sent", dropped + " earlier report(s): too many at once"));
        }
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Hawkeye #" + report.number() + " of this run; repeats go to the file only");
        embed.add("footer", footer);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Hawkeye");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        return payload;
    }

    /**
     * The whole {@code multipart/form-data} body.
     *
     * @param boundary the part boundary (also in the {@code Content-Type} header)
     * @param json     the {@code payload_json} part
     * @param fileName the attachment's name
     * @param file     the attachment's bytes; {@code null} for none
     * @return the body
     */
    public static byte @NotNull [] multipart(@NotNull String boundary, @NotNull JsonObject json,
                                              @NotNull String fileName, byte @Nullable [] file) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + json + "\r\n");
        if (file != null) {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                    + fileName.replace("\"", "") + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n");
            body.writeBytes(file);
            write(body, "\r\n");
        }
        write(body, "--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private static @NotNull String what(@NotNull WrittenReport report) {
        if (report.kind() == ProblemKind.CRASH) {
            return "Crash";
        }
        return "WARN".equals(report.level()) ? "Warning" : "Error";
    }

    private static int color(@NotNull WrittenReport report) {
        if (report.kind() == ProblemKind.CRASH) {
            return CRASH_COLOR;
        }
        return "WARN".equals(report.level()) ? WARNING_COLOR : ERROR_COLOR;
    }

    private static @NotNull JsonObject field(@NotNull String name, @NotNull String value) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", false);
        return field;
    }

    private static @NotNull String cut(@NotNull String text, int max) {
        return text.length() <= max ? text : TextCuts.head(text, max - 3) + "...";
    }

    private static void write(@NotNull ByteArrayOutputStream body, @NotNull String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
