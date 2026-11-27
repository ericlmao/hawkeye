package gg.moonrise.hawkeye.core.service.dev;

import org.jetbrains.annotations.NotNull;

/** How a run of fake data ended, or why it did not start. */
public sealed interface DevDataOutcome {

    /**
     * Every asked-for event is written.
     *
     * @param totals the run's final progress
     */
    record Finished(@NotNull DevDataProgress totals) implements DevDataOutcome {
    }

    /**
     * The run stopped early ({@code /hawkeye dev cancel}, or dev mode turned off); what it wrote stays.
     *
     * @param totals the run's progress when it stopped
     */
    record Cancelled(@NotNull DevDataProgress totals) implements DevDataOutcome {
    }

    /** Dev mode is off ({@code config.yml: dev-mode.enabled}). */
    record Disabled() implements DevDataOutcome {
    }

    /** Another run is going on this server. */
    record AlreadyRunning() implements DevDataOutcome {
    }

    /** The home database is not connected yet. */
    record NotReady() implements DevDataOutcome {
    }

    /** The home database has no node id left for another run (each takes one of 1023). */
    record NoNodeLeft() implements DevDataOutcome {
    }

    /**
     * Registering the node or writing failed; what was written before stays.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements DevDataOutcome {
    }
}
