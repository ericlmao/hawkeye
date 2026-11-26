package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.text.TextCuts;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One logged warning or error, copied out of the logging event on the thread that logged it (a log4j event and
 * its message may be reused once the call returns).
 *
 * @param timeMillis when it was logged, epoch milliseconds
 * @param kind       where it is filed
 * @param level      the log level name ({@code WARN}, {@code ERROR})
 * @param logger     the logger name
 * @param thread     the name of the thread that logged it
 * @param template   the message before its parameters were filled in ({@code "Could not write {} event(s)"})
 * @param message    the message as logged
 * @param thrown     the exception logged with it, if any
 */
public record Problem(long timeMillis, @NotNull ProblemKind kind, @NotNull String level, @NotNull String logger,
                      @NotNull String thread, @NotNull String template, @NotNull String message,
                      @Nullable Throwable thrown) {

    /** How deep a cause chain is followed (a chain can loop). */
    private static final int MAX_CAUSES = 16;

    /** The longest exception message the console line quotes. */
    private static final int MAX_REASON = 300;

    /**
     * What makes two problems the same: kind, level, logger, the message template (so "attempt 3" and
     * "attempt 4" of one retry loop match) and the exception classes of the cause chain (so a different
     * failure at the same place does not).
     *
     * @return the key repeats share
     */
    public @NotNull String fingerprint() {
        StringBuilder key = new StringBuilder(kind.name()).append('\n').append(level).append('\n').append(logger)
                .append('\n').append(template);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = thrown; cause != null && seen.size() < MAX_CAUSES && seen.add(cause);
                cause = cause.getCause()) {
            key.append('\n').append(cause.getClass().getName());
        }
        return key.toString();
    }

    /**
     * The message with the innermost cause of its exception, on one line: what the console shows.
     *
     * @return {@code message}, or {@code message: SQLException: database is locked}
     */
    public @NotNull String headline() {
        Throwable root = rootCause();
        if (root == null) {
            return oneLine(message);
        }
        String type = root.getClass().getSimpleName().isEmpty() ? root.getClass().getName()
                : root.getClass().getSimpleName();
        String reason = root.getMessage();
        String cause = reason == null || reason.isBlank() ? type : type + ": " + shorten(oneLine(reason));
        return oneLine(message) + ": " + cause;
    }

    private @Nullable Throwable rootCause() {
        Throwable root = thrown;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root != null && root.getCause() != null && seen.size() < MAX_CAUSES && seen.add(root)) {
            root = root.getCause();
        }
        return root;
    }

    private static @NotNull String oneLine(@NotNull String text) {
        return text.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    private static @NotNull String shorten(@NotNull String text) {
        return text.length() <= MAX_REASON ? text : TextCuts.head(text, MAX_REASON) + "...";
    }
}
