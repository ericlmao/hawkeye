package gg.moonrise.hawkeye.core.diagnostic.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import gg.moonrise.hawkeye.core.diagnostic.WrittenReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class DiscordPayloadTest {

    private static final long AT = Instant.parse("2026-11-25T14:03:05Z").toEpochMilli();

    @Test
    public void aCrashIsOneEmbedWithTheMessageInACodeBlockAndNoMentions() {
        JsonObject json = DiscordPayload.json(report(ProblemKind.CRASH, "ERROR", "Hawkeye could not load: boom"),
                "lobby", "Paper 26.3-262 (MC: 26.3)", "plugins/Hawkeye/crash/Crash-2026-11-25-14-03.txt", true, 0);

        assertEquals("Hawkeye", json.get("username").getAsString());
        assertEquals(0, json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonObject embed = json.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Crash on lobby", embed.get("title").getAsString());
        assertEquals("```\nHawkeye could not load: boom\n```", embed.get("description").getAsString());
        assertEquals(0xE74C3C, embed.get("color").getAsInt());
        assertEquals("2026-11-25T14:03:05Z", embed.get("timestamp").getAsString());
        assertEquals("plugins/Hawkeye/crash/Crash-2026-11-25-14-03.txt",
                embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("Paper 26.3-262 (MC: 26.3)",
                embed.getAsJsonArray("fields").get(1).getAsJsonObject().get("value").getAsString());
        assertEquals(2, embed.getAsJsonArray("fields").size());
    }

    @Test
    public void warningsAndErrorsAreToldApart() {
        JsonObject warning = embed(DiscordPayload.json(report(ProblemKind.ERROR, "WARN", "Could not connect"),
                "lobby", "Paper", "errors/Error.txt", true, 0));
        JsonObject error = embed(DiscordPayload.json(report(ProblemKind.ERROR, "ERROR", "The writer failed"),
                "lobby", "Paper", "errors/Error.txt", true, 0));

        assertEquals("Warning on lobby", warning.get("title").getAsString());
        assertEquals(0xF1C40F, warning.get("color").getAsInt());
        assertEquals("Error on lobby", error.get("title").getAsString());
        assertEquals(0xE67E22, error.get("color").getAsInt());
    }

    @Test
    public void textCannotCloseTheCodeBlockOrOverflowTheEmbed() {
        String message = "```@everyone``` " + "x".repeat(5000);

        JsonObject embed = embed(DiscordPayload.json(report(ProblemKind.ERROR, "ERROR", message), "lobby", "Paper",
                "errors/Error.txt", false, 3));

        String description = embed.get("description").getAsString();
        assertTrue(description.length() <= 4096, Integer.toString(description.length()));
        assertTrue(description.startsWith("```\n`\u200b`\u200b`\u200b@everyone`\u200b`\u200b`\u200b x"),
                description);
        assertTrue(description.endsWith("...\n```"), description);
        assertEquals(1, description.split("```", -1).length - 2, "only the opening and closing fences");
        assertTrue(embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString()
                .endsWith("(too large to attach)"));
        assertTrue(embed.getAsJsonArray("fields").get(2).getAsJsonObject().get("value").getAsString()
                .startsWith("3 earlier report(s)"));
    }

    @Test
    public void aCutTitleDoesNotSplitASurrogatePair() {
        String smiley = "\uD83D\uDE00";
        // "Error on " is 9 characters, so the smiley's first half is the 253rd: the last one kept before "...".
        String serverName = "s".repeat(243) + smiley + "more";

        JsonObject embed = embed(DiscordPayload.json(report(ProblemKind.ERROR, "ERROR", "Failed"), serverName,
                "Paper", "errors/Error.txt", true, 0));

        assertEquals("Error on " + "s".repeat(243) + "...", embed.get("title").getAsString());
    }

    @Test
    public void theBodyIsMultipartWithTheJsonAndTheFile() {
        JsonObject json = new JsonObject();
        json.addProperty("username", "Hawkeye");
        byte[] file = "Hawkeye error report\n#1 ERROR".getBytes(StandardCharsets.UTF_8);

        String body = new String(DiscordPayload.multipart("b0undary", json, "Error-2026-11-25-14-03.txt", file),
                StandardCharsets.UTF_8);

        assertEquals("--b0undary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n{\"username\":\"Hawkeye\"}\r\n"
                + "--b0undary\r\nContent-Disposition: form-data; name=\"files[0]\"; "
                + "filename=\"Error-2026-11-25-14-03.txt\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                + "Hawkeye error report\n#1 ERROR\r\n--b0undary--\r\n", body);
        String withoutFile = new String(DiscordPayload.multipart("b", json, "x.txt", null), StandardCharsets.UTF_8);
        assertFalse(withoutFile.contains("files[0]"), withoutFile);
    }

    private static @NotNull JsonObject embed(@NotNull JsonObject json) {
        return json.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static @NotNull WrittenReport report(@NotNull ProblemKind kind, @NotNull String level,
                                                 @NotNull String headline) {
        return new WrittenReport(1, kind, level, headline, Path.of("errors", "Error.txt"), AT);
    }
}
