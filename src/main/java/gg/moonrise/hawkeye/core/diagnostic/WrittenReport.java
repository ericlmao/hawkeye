package gg.moonrise.hawkeye.core.diagnostic;

import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * A problem's first time, now in its file: what the webhook sends.
 *
 * @param number     its number in this run ({@code #3} in the file)
 * @param kind       where it is filed
 * @param level      the log level name
 * @param headline   its one-line message, as the console shows it
 * @param file       the file holding it
 * @param timeMillis when it was logged
 */
public record WrittenReport(int number, @NotNull ProblemKind kind, @NotNull String level, @NotNull String headline,
                            @NotNull Path file, long timeMillis) {
}
