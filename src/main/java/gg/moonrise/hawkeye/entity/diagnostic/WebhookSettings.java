package gg.moonrise.hawkeye.entity.diagnostic;

import org.jetbrains.annotations.NotNull;

/**
 * Where the error log sends the first time of each problem, read from {@code config.yml}'s {@code error-reports}
 * section with every {@code ${NAME}} resolved.
 *
 * @param url        the Discord webhook URL; empty for none. It holds the webhook's token: never log or show it
 * @param errors     whether errors and warnings ({@code errors/}) are sent
 * @param crashes    whether crashes ({@code crash/}) are sent
 * @param serverName this server's name ({@code server.name}), in the message title
 */
public record WebhookSettings(@NotNull String url, boolean errors, boolean crashes, @NotNull String serverName) {

    /** No webhook. */
    public static final WebhookSettings OFF = new WebhookSettings("", false, false, "");

    /**
     * Whether a problem of a kind is sent.
     *
     * @param kind where the problem is filed
     * @return {@code true} when there is a URL and its kind is switched on
     */
    public boolean sends(@NotNull ProblemKind kind) {
        if (url.isBlank()) {
            return false;
        }
        return switch (kind) {
            case ERROR -> errors;
            case CRASH -> crashes;
        };
    }

    @Override
    public @NotNull String toString() {
        return "WebhookSettings[url=" + (url.isBlank() ? "none" : "<redacted>") + ", errors=" + errors + ", crashes="
                + crashes + ", serverName=" + serverName + "]";
    }
}
