package gg.moonrise.hawkeye.core.diagnostic;

import org.jetbrains.annotations.NotNull;

/** Told by the {@link ErrorLog}, on its writer thread, about each problem's first time once its file is written. */
@FunctionalInterface
public interface ReportListener {

    /** A listener that does nothing. */
    ReportListener NONE = report -> {
    };

    /**
     * A problem's first time was written. Must not block for long: the writer thread waits.
     *
     * @param report the problem and its file
     */
    void written(@NotNull WrittenReport report);
}
