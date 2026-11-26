package gg.moonrise.hawkeye.core.diagnostic;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNull;

/**
 * What the console would print: an appender on the test log4j's root logger, behind every root filter (the error
 * log's included), exactly where Paper's console appender sits.
 */
public final class ConsoleLines extends AbstractAppender implements AutoCloseable {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private final LoggerConfig root;

    private ConsoleLines(@NotNull LoggerConfig root) {
        super("TestConsole", null, null, true, Property.EMPTY_ARRAY);
        this.root = root;
    }

    /**
     * Starts recording.
     *
     * @return the recorder; close it at the end of the test
     */
    public static @NotNull ConsoleLines attach() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        ConsoleLines lines = new ConsoleLines(context.getConfiguration().getRootLogger());
        lines.start();
        lines.root.addAppender(lines, null, null);
        return lines;
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * The events printed so far.
     *
     * @return the events, oldest first
     */
    public @NotNull List<LogEvent> events() {
        return List.copyOf(events);
    }

    /**
     * The printed events of one logger.
     *
     * @param logger the logger name
     * @return its events, oldest first
     */
    public @NotNull List<LogEvent> of(@NotNull String logger) {
        return events.stream().filter(event -> logger.equals(event.getLoggerName())).toList();
    }

    @Override
    public void close() {
        root.removeAppender(getName());
        stop();
    }
}
