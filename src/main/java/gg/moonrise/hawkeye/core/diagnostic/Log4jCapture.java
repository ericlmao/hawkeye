package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.core.sql.DatabaseFactory;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A log4j filter on the server's root logger (every Paper server logs through log4j-core, 2.22.1 on 1.21.1 up to
 * 2.26.0 on 26.3) that hands Hawkeye's warnings and errors to the {@link ErrorLog}. It sees an event on the
 * thread that logged it, before any appender, so it decides at once:
 * <ul>
 *     <li>the first time: the event is dropped and replaced by one line without the stack trace that names the
 *     file holding it;</li>
 *     <li>a repeat: the event is dropped (the file records it);</li>
 *     <li>anything else passes as it is.</li>
 * </ul>
 * Hawkeye's events are those of the {@code gg.moonrise.hawkeye} loggers (the relocated engine included), of the
 * plugin's own logger ({@code Hawkeye}: Paper's "Could not pass event" and task failures) and HikariCP's about a
 * Hawkeye pool ({@link DatabaseFactory#POOL_PREFIX}), plus, while a thread runs Hawkeye's load or enable, every
 * event of that thread (Moss and Spring starting the context) except another plugin's: Paper names a plugin's logger
 * after the plugin, and Hawkeye's enable runs other plugins' code too (its {@code ServicesManager} registration fires
 * {@code ServiceRegisterEvent} to their listeners, and Paper logs their failures under their name). A
 * {@link LogMarkers#CRASH} event, or an ERROR of that startup thread, is a crash.
 *
 * <p>The filter follows the root logger when the logging is reconfigured (another plugin calling
 * {@code reconfigure}, a {@code monitorInterval} reload): log4j builds a new configuration then, without it.
 */
public final class Log4jCapture extends AbstractFilter implements ConsoleCapture {

    private static final String BASE_LOGGER = "gg.moonrise.hawkeye";
    private static final String HIKARI_LOGGER = "com.zaxxer.hikari";
    private static final Marker REPORTED = MarkerManager.getMarker(LogMarkers.REPORTED_NAME);

    private final @NotNull ErrorLog errorLog;
    private final @NotNull String pluginLogger;
    private final @NotNull Predicate<String> otherPlugin;
    private final @NotNull Path serverFolder;
    private final @NotNull LoggerContext context;
    private final @NotNull PropertyChangeListener reconfigured = this::reconfigured;
    private final Object lock = new Object();

    // Guarded by lock.
    private @Nullable LoggerConfig root;
    private boolean closed;

    private Log4jCapture(@NotNull ErrorLog errorLog, @NotNull String pluginLogger,
                         @NotNull Predicate<String> otherPlugin, @NotNull Path serverFolder,
                         @NotNull LoggerContext context) {
        super(Result.NEUTRAL, Result.NEUTRAL);
        this.errorLog = errorLog;
        this.pluginLogger = pluginLogger;
        this.otherPlugin = otherPlugin;
        this.serverFolder = serverFolder;
        this.context = context;
    }

    /**
     * Adds the filter to the root logger of the logging context Hawkeye's loggers use, and keeps it there when that
     * context is reconfigured.
     *
     * @param errorLog     where problems go
     * @param pluginLogger the plugin's logger name (its name, {@code Hawkeye})
     * @param otherPlugin  whether a logger name is another plugin's; asked only on the startup thread, for an event
     *                     that is not otherwise Hawkeye's
     * @param serverFolder the server's folder: the console line names files relative to it
     * @return the installed capture, empty when the logging is not log4j-core
     */
    public static @NotNull Optional<ConsoleCapture> install(@NotNull ErrorLog errorLog, @NotNull String pluginLogger,
                                                            @NotNull Predicate<String> otherPlugin,
                                                            @NotNull Path serverFolder) {
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return Optional.empty();
        }
        Log4jCapture capture = new Log4jCapture(errorLog, pluginLogger, otherPlugin,
                serverFolder.toAbsolutePath().normalize(), context);
        // Listening first: a reconfiguration from now on moves the filter, one before it is attached to below.
        context.addPropertyChangeListener(capture.reconfigured);
        try {
            capture.attach(context.getConfiguration());
        } catch (RuntimeException e) {
            capture.close();
            throw e;
        }
        return Optional.of(capture);
    }

    @Override
    public void close() {
        context.removePropertyChangeListener(reconfigured);
        synchronized (lock) {
            closed = true;
            if (root != null) {
                root.removeFilter(this);
                root = null;
            }
        }
        stop();
    }

    /** The logging context switched configurations: log4j fires this after it stopped the old one. */
    private void reconfigured(@NotNull PropertyChangeEvent event) {
        if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                && event.getNewValue() instanceof Configuration configuration) {
            attach(configuration);
        }
    }

    /** Moves the filter to a configuration's root logger, unless it is there already or the capture is closed. */
    private void attach(@NotNull Configuration configuration) {
        synchronized (lock) {
            LoggerConfig next = configuration.getRootLogger();
            if (closed || next == root) {
                return;
            }
            if (root != null) {
                root.removeFilter(this);
            }
            // Stopping the old configuration stopped its filters, this one included.
            if (!isStarted()) {
                start();
            }
            next.addFilter(this);
            root = next;
        }
    }

    @Override
    public Result filter(LogEvent event) {
        Level level = event.getLevel();
        if (level == null || !level.isMoreSpecificThan(Level.WARN)) {
            return Result.NEUTRAL;
        }
        Marker marker = event.getMarker();
        if (marker != null && marker.isInstanceOf(REPORTED)) {
            return Result.NEUTRAL;
        }
        boolean startup = errorLog.isStartupThread(event.getThreadId());
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        if (!isHawkeye(logger) && !isHawkeyePool(logger, event) && (!startup || otherPlugin.test(logger))) {
            return Result.NEUTRAL;
        }
        boolean crash = (marker != null && marker.isInstanceOf(LogMarkers.CRASH_NAME))
                || (startup && level.isMoreSpecificThan(Level.ERROR));
        Problem problem = problem(event, logger, crash ? ProblemKind.CRASH : ProblemKind.ERROR);
        return switch (errorLog.report(problem)) {
            case ReportResult.First first -> {
                LogManager.getLogger(logger).log(level, REPORTED, "{} (details in {})", problem.headline(),
                        ReportFormat.shownPath(serverFolder, first.file()));
                yield Result.DENY;
            }
            case ReportResult.Repeat repeat -> Result.DENY;
            case ReportResult.Untracked untracked -> Result.NEUTRAL;
        };
    }

    private boolean isHawkeye(@NotNull String logger) {
        return logger.equals(pluginLogger) || logger.equals(BASE_LOGGER) || logger.startsWith(BASE_LOGGER + ".");
    }

    /** HikariCP's own warnings about one of Hawkeye's pools ("Hawkeye-home - Pool is empty, ..."). */
    private static boolean isHawkeyePool(@NotNull String logger, @NotNull LogEvent event) {
        Message message = event.getMessage();
        return logger.startsWith(HIKARI_LOGGER + ".") && message != null
                && String.valueOf(message.getFormattedMessage()).startsWith(DatabaseFactory.POOL_PREFIX);
    }

    private static @NotNull Problem problem(@NotNull LogEvent event, @NotNull String logger,
                                            @NotNull ProblemKind kind) {
        Message message = event.getMessage();
        String formatted = message == null ? "" : String.valueOf(message.getFormattedMessage());
        String template = message == null || message.getFormat() == null ? formatted : message.getFormat();
        String thread = event.getThreadName() == null ? "?" : event.getThreadName();
        return new Problem(event.getTimeMillis(), kind, event.getLevel().name(), logger, thread, template, formatted,
                event.getThrown());
    }
}
