package gg.moonrise.hawkeye.entity.diagnostic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/** Where a logged problem is filed: {@code errors/Error-...txt} or {@code crash/Crash-...txt}. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ProblemKind {

    /** A warning or error while Hawkeye runs. */
    ERROR("errors", "Error", "error report"),

    /** Hawkeye could not start (load or enable failed), or one of its threads stopped. */
    CRASH("crash", "Crash", "crash report");

    /** The folder under the plugin's data folder. */
    private final @NotNull String folder;

    /** The file name prefix. */
    private final @NotNull String filePrefix;

    /** What the file's first line calls it. */
    private final @NotNull String title;
}
