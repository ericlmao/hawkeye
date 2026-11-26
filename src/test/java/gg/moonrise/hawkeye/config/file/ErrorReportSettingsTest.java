package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ErrorReportSettingsTest {

    private static final String URL = "https://discord.com/api/webhooks/123/secret-token";
    private static final Environment NONE = name -> Optional.empty();

    @TempDir
    private Path folder;

    @Test
    public void theDefaultsSendNothingAndNoticeStaff() throws IOException {
        HawkeyeConfig config = HawkeyeConfigProvider.readConfig(folder);

        ErrorReportSettings settings = config.errorReports();
        assertTrue(settings.joinNotice());
        assertTrue(settings.webhookErrors());
        assertTrue(settings.webhookCrashes());
        assertTrue(settings.problems(NONE).isEmpty());
        WebhookSettings webhook = settings.webhook(NONE, "lobby");
        assertFalse(webhook.sends(ProblemKind.ERROR));
        assertFalse(webhook.sends(ProblemKind.CRASH));
        String file = Files.readString(folder.resolve(HawkeyeConfigProvider.CONFIG_FILE)).replace("\r\n", "\n");
        assertTrue(file.contains("error-reports:\n"), file);
        assertTrue(file.contains("  webhook-url: ''"), file);
        assertTrue(file.contains("  join-notice: true"), file);
    }

    @Test
    public void theUrlMayComeFromAnEnvironmentVariable() throws IOException {
        Files.writeString(folder.resolve(HawkeyeConfigProvider.CONFIG_FILE),
                "error-reports:\n  webhook-url: '${HAWKEYE_WEBHOOK_URL}'\n  webhook-errors: false\n");
        ErrorReportSettings settings = HawkeyeConfigProvider.readConfig(folder).errorReports();
        Environment environment = name -> Optional.ofNullable(Map.of("HAWKEYE_WEBHOOK_URL", URL).get(name));

        WebhookSettings webhook = settings.webhook(environment, "lobby");

        assertEquals(new WebhookSettings(URL, false, true, "lobby"), webhook);
        assertFalse(webhook.sends(ProblemKind.ERROR));
        assertTrue(webhook.sends(ProblemKind.CRASH));
        assertTrue(settings.problems(environment).isEmpty());
        assertFalse(webhook.toString().contains("secret-token"), webhook.toString());
        assertFalse(settings.toString().contains("HAWKEYE_WEBHOOK_URL"), settings.toString());
    }

    @Test
    public void anUnsetVariableIsAProblemThatNamesIt() throws IOException {
        Files.writeString(folder.resolve(HawkeyeConfigProvider.CONFIG_FILE),
                "error-reports:\n  webhook-url: '${HAWKEYE_WEBHOOK_URL}'\n");
        ErrorReportSettings settings = HawkeyeConfigProvider.readConfig(folder).errorReports();

        List<String> problems = settings.problems(NONE);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("HAWKEYE_WEBHOOK_URL"), problems.getFirst());
        assertEquals(WebhookSettings.OFF, settings.webhook(NONE, "lobby"));
    }

    @Test
    public void aUrlThatIsNoWebUrlIsAProblemThatDoesNotQuoteIt() {
        for (String url : List.of("discord.com/api/webhooks/1/secret-token", "ftp://example.com/secret-token",
                "https:///secret-token")) {
            List<String> problems = settingsWith(url).problems(NONE);

            assertEquals(1, problems.size(), url + ": " + problems);
            assertTrue(problems.getFirst().startsWith("error-reports.webhook-url must be a Discord webhook URL"));
            assertFalse(problems.getFirst().contains("secret-token"), problems.getFirst());
        }
        assertTrue(settingsWith(URL).problems(NONE).isEmpty());
        assertTrue(settingsWith("http://127.0.0.1:8080/relay").problems(NONE).isEmpty(), "a local relay");
    }

    private @NotNull ErrorReportSettings settingsWith(@NotNull String url) {
        try {
            Path config = folder.resolve(HawkeyeConfigProvider.CONFIG_FILE);
            Files.writeString(config, "error-reports:\n  webhook-url: '" + url + "'\n");
            return HawkeyeConfigProvider.readConfig(folder).errorReports();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
