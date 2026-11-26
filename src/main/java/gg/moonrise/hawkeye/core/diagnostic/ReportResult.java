package gg.moonrise.hawkeye.core.diagnostic;

import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/** What {@link ErrorLog#report} did with a problem, which decides what the console shows. */
public sealed interface ReportResult {

    /**
     * The first time this problem was logged in this run: it is written to {@code file}, and the console shows
     * it once.
     *
     * @param file   the file it is written to
     * @param number its number in this run ({@code #3} in the file)
     */
    record First(@NotNull Path file, int number) implements ReportResult {
    }

    /**
     * The problem was logged before in this run: only the file of its first time records it, the console
     * stays quiet.
     *
     * @param file       the file of its first time
     * @param number     its number in this run
     * @param occurrence how often it has been logged now, this time included
     */
    record Repeat(@NotNull Path file, int number, long occurrence) implements ReportResult {
    }

    /**
     * The error log did not take the problem (it is closed, cannot write its files, or already tracks too many
     * different problems): it is logged as it would be without the error log.
     *
     * @param reason why, for tests and diagnostics
     */
    record Untracked(@NotNull String reason) implements ReportResult {
    }
}
