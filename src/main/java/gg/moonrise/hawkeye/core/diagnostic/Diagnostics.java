package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.env.ProcessEnvironment;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.diagnostic.webhook.DiscordWebhook;
import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.entity.diagnostic.PendingReport;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hawkeye's error and crash files: the {@link ErrorLog}, its hook in the server's logging, the Discord webhook and
 * the files waiting for review. The plugin opens it first thing in {@code onLoad}, before the Spring context
 * exists (so a context that fails to start is a crash file, and reaches the webhook), registers it in the context
 * for the beans that use it, and closes it last in {@code onDisable}. Not a scanned bean for that reason.
 *
 * <p>The webhook settings come from {@code config.yml} as it is at load; the error report service replaces them
 * with the validated, reloadable configuration once the context runs.
 */
@Slf4j
public final class Diagnostics implements AutoCloseable {

    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final @NotNull ErrorLog errorLog;
    private final @Nullable ConsoleCapture capture;
    private final @NotNull DiscordWebhook webhook;
    private final @NotNull ReportFiles files;
    private final @NotNull Settings settings;

    private Diagnostics(@NotNull ErrorLog errorLog, @Nullable ConsoleCapture capture, @NotNull DiscordWebhook webhook,
                        @NotNull ReportFiles files, @NotNull Settings settings) {
        this.errorLog = errorLog;
        this.capture = capture;
        this.webhook = webhook;
        this.files = files;
        this.settings = settings;
    }

    /**
     * Starts the error log in a plugin's data folder and hooks it into the server's logging.
     *
     * @param plugin Hawkeye
     * @return the open diagnostics
     */
    public static @NotNull Diagnostics open(@NotNull Plugin plugin) {
        Path dataFolder = plugin.getDataPath();
        Path serverFolder = Path.of("").toAbsolutePath().normalize();
        ServerDetails details = new ServerDetails(plugin);
        ServerSnapshot server = details.snapshot();
        DiscordWebhook webhook = new DiscordWebhook(file -> ReportFormat.shownPath(serverFolder, file),
                server.serverName() + " " + server.serverVersion() + ", Hawkeye " + server.hawkeyeVersion());
        Settings settings = new Settings(earlySettings(dataFolder));
        ErrorLog errorLog = new ErrorLog(dataFolder, Clock.systemDefaultZone(), details::snapshot,
                report -> webhook.submit(report, settings.get()));
        errorLog.start();
        ConsoleCapture capture = null;
        RuntimeException failure = null;
        try {
            capture = Log4jCapture.install(errorLog, plugin.getName(), logger -> isOtherPlugin(plugin, logger),
                    serverFolder).orElse(null);
        } catch (LinkageError e) {
            // A server without log4j-core: nothing to hook into.
        } catch (RuntimeException e) {
            // An odd logging configuration. The error log is optional: Hawkeye runs on without it.
            failure = e;
        }
        if (capture == null) {
            if (failure == null) {
                log.warn("The server does not log through log4j-core; Hawkeye's errors are not written to files");
            } else {
                log.warn(LogMarkers.REPORTED, "Hawkeye could not hook into the server's logging; its errors are not "
                        + "written to files", failure);
            }
            errorLog.close(CLOSE_WAIT);
        }
        return new Diagnostics(errorLog, capture, webhook, new ReportFiles(dataFolder), settings);
    }

    /**
     * The error log.
     *
     * @return the error log
     */
    public @NotNull ErrorLog errorLog() {
        return errorLog;
    }

    /** Marks the calling thread as running Hawkeye's load or enable (see {@link ErrorLog#startupBegins()}). */
    public void startupBegins() {
        errorLog.startupBegins();
    }

    /** Ends {@link #startupBegins()}. */
    public void startupEnds() {
        errorLog.startupEnds();
    }

    /**
     * Where the webhook settings come from from now on; asked once per report.
     *
     * @param source the settings source (the reloadable configuration)
     */
    public void useWebhookSettings(@NotNull Supplier<WebhookSettings> source) {
        settings.source = source;
    }

    /**
     * The files waiting for review. File I/O: call it off the server threads.
     *
     * @return crashes first, then errors, oldest first
     * @throws IOException when a folder or file cannot be read
     */
    public @NotNull List<PendingReport> pendingReports() throws IOException {
        return files.pending();
    }

    /**
     * How many files wait for review, without reading them. File I/O: call it off the server threads.
     *
     * @return the waiting crash and error files
     * @throws IOException when a folder cannot be listed
     */
    public @NotNull PendingCount pendingCount() throws IOException {
        return files.waiting();
    }

    /**
     * Moves every waiting file to its folder's {@code reviewed} folder, on the error log's thread.
     *
     * @return how many files were moved; fails when a file cannot be moved
     */
    public @NotNull CompletableFuture<Integer> markReviewed() {
        return errorLog.markReviewed(files);
    }

    /** Removes the logging hook, writes what is left, sends what the webhook still holds, and stops. */
    @Override
    public void close() {
        if (capture != null) {
            capture.close();
        }
        errorLog.close(CLOSE_WAIT);
        webhook.close(CLOSE_WAIT);
    }

    /**
     * Whether a logger is another plugin's: Paper names a plugin's logger after the plugin, or after its
     * {@code prefix}. Asked on the thread running Hawkeye's load or enable (the main thread) only, for a warning or
     * an error that is not otherwise Hawkeye's.
     */
    private static boolean isOtherPlugin(@NotNull Plugin hawkeye, @NotNull String logger) {
        if (logger.isEmpty() || logger.equals(hawkeye.getName())) {
            return false;
        }
        try {
            PluginManager plugins = hawkeye.getServer().getPluginManager();
            if (plugins.getPlugin(logger) != null) {
                return true;
            }
            for (Plugin other : plugins.getPlugins()) {
                if (other != hawkeye && logger.equals(other.getPluginMeta().getLoggerPrefix())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The webhook as {@code config.yml} has it now, read without the Spring context. A file that does not load or
     * a variable that is not set gives no webhook; the configuration provider reports those once it runs.
     */
    private static @NotNull WebhookSettings earlySettings(@NotNull Path dataFolder) {
        Environment environment = new ProcessEnvironment();
        try {
            HawkeyeConfig config = HawkeyeConfigProvider.readConfig(dataFolder);
            if (!config.errorReports().problems(environment).isEmpty()) {
                return WebhookSettings.OFF;
            }
            return config.errorReports().webhook(environment, config.server().name());
        } catch (IOException | RuntimeException e) {
            return WebhookSettings.OFF;
        }
    }

    /** The current webhook settings source, read by the error log's writer thread. */
    private static final class Settings implements Supplier<WebhookSettings> {

        private volatile @NotNull Supplier<WebhookSettings> source;

        private Settings(@NotNull WebhookSettings initial) {
            this.source = () -> initial;
        }

        @Override
        public @NotNull WebhookSettings get() {
            try {
                return source.get();
            } catch (RuntimeException e) {
                return WebhookSettings.OFF;
            }
        }
    }
}
