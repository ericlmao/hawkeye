package gg.moonrise.hawkeye.entity.diagnostic;

import org.jetbrains.annotations.NotNull;

/**
 * An error or crash file waiting to be reviewed: one in {@code errors/} or {@code crash/} itself, not yet moved to
 * their {@code reviewed/} folders.
 *
 * @param kind           which folder it is in
 * @param fileName       its name ({@code Error-2026-11-25-14-03.txt})
 * @param modifiedMillis when it was last written, epoch milliseconds
 * @param sizeBytes      its size
 * @param problems       how many problems it starts (entries {@code #1}, {@code #2}, ...)
 * @param firstProblem   the message of its first problem; empty when the file holds none
 */
public record PendingReport(@NotNull ProblemKind kind, @NotNull String fileName, long modifiedMillis, long sizeBytes,
                            int problems, @NotNull String firstProblem) {
}
