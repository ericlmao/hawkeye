package gg.moonrise.hawkeye.core.service.dev;

import org.jetbrains.annotations.NotNull;

/**
 * Hears how a run of fake data goes while it runs; the final result is the returned {@link DevDataOutcome}. The
 * command implements it to tell the sender. {@link #started} arrives on the {@code Hawkeye-Dev} thread and
 * {@link #progress} on the {@code Hawkeye-Maintenance} thread: hop to the right thread before touching Bukkit
 * state.
 */
public interface DevDataListener {

    /** Hears nothing. */
    DevDataListener NONE = new DevDataListener() {
    };

    /**
     * The run's node is registered and generating begins.
     *
     * @param progress where it stands (nothing written yet)
     */
    default void started(@NotNull DevDataProgress progress) {
    }

    /**
     * The run is still going; called every {@link DevDataService#PROGRESS_INTERVAL} until it ends, never after
     * its outcome.
     *
     * @param progress where it stands
     */
    default void progress(@NotNull DevDataProgress progress) {
    }
}
