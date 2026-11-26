package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.SerializeWith;
import gg.moonrise.hawkeye.config.env.EnvReferences;
import gg.moonrise.hawkeye.config.env.EnvValue;
import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code error-reports} section: the Discord webhook that gets the first time of each problem, and the notice
 * staff get on join about files waiting for review. Every key applies on {@code /hawkeye reload}. The webhook URL
 * carries the webhook's token, so it may be a {@code ${NAME}} reference and is never shown by {@link #toString()}
 * or quoted by a validation problem.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class ErrorReportSettings {

    @Comment({"A Discord webhook URL (https://discord.com/api/webhooks/...) that gets the first time of every error,",
            "warning and crash with its file attached; empty for none. Repeats are never sent. The URL holds the",
            "webhook's token: put it in an environment variable, ${HAWKEYE_WEBHOOK_URL}."})
    @SerializeWith(serializer = TextValueSerializer.class)
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private String webhookUrl = "";

    @Comment("Send errors and warnings (plugins/Hawkeye/errors) to the webhook.")
    private boolean webhookErrors = true;

    @Comment("Send crashes (plugins/Hawkeye/crash) to the webhook.")
    private boolean webhookCrashes = true;

    @Comment({"Tell players with hawkeye.errors (OPs by default) when they join how many error and crash files",
            "wait to be reviewed (/hawkeye errors lists them, /hawkeye errors reviewed moves them to reviewed/)."})
    private boolean joinNotice = true;

    /**
     * The webhook settings with the URL resolved. Validation ({@link #problems}) makes sure it resolves.
     *
     * @param environment where variables are looked up
     * @param serverName  this server's name
     * @return the settings; {@link WebhookSettings#OFF} when a referenced variable is not set
     */
    public @NotNull WebhookSettings webhook(@NotNull Environment environment, @NotNull String serverName) {
        return switch (EnvReferences.resolve(url(), environment)) {
            case EnvValue.Resolved url -> new WebhookSettings(url.value().strip(), webhookErrors, webhookCrashes,
                    serverName);
            case EnvValue.Unset unset -> WebhookSettings.OFF;
        };
    }

    /**
     * Validation problems. A problem names an unset variable or what is wrong with the URL, never the URL.
     *
     * @param environment where variables are looked up
     * @return one message per problem
     */
    public @NotNull List<String> problems(@NotNull Environment environment) {
        List<String> problems = new ArrayList<>();
        switch (EnvReferences.resolve(url(), environment)) {
            case EnvValue.Resolved url when !url.value().isBlank() && !isWebUrl(url.value().strip()) ->
                    problems.add("error-reports.webhook-url must be a Discord webhook URL "
                            + "(https://discord.com/api/webhooks/...) or empty");
            case EnvValue.Resolved url -> {
                // Empty (no webhook) or a usable URL.
            }
            case EnvValue.Unset unset -> unset.variables().forEach(variable ->
                    problems.add(EnvReferences.unsetMessage("error-reports.webhook-url", variable)));
        }
        return problems;
    }

    private @NotNull String url() {
        return Objects.requireNonNullElse(webhookUrl, "");
    }

    /** An http(s) URL with a host; http is accepted for a local relay. */
    private static boolean isWebUrl(@NotNull String text) {
        try {
            URI uri = new URI(text);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
