package gg.moonrise.hawkeye.core.service.dev;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * What one run of {@code /hawkeye dev generate} is asked to write.
 *
 * @param count       how many fake events, at least 1
 * @param days        how many days back they reach, at least 1
 * @param worlds      the worlds they happen in, the primary one first; at most {@link #MAX_WORLDS} are kept, and
 *                    none means {@link DevWorld#DEFAULTS}
 * @param requestedBy who asked (a player name or the console's), for the log
 */
public record DevDataRequest(int count, int days, @NotNull List<DevWorld> worlds, @NotNull String requestedBy) {

    /** How many days back fake data reaches when the command names none. */
    public static final int DEFAULT_DAYS = 30;

    /** The most worlds fake data is spread over. */
    public static final int MAX_WORLDS = 8;

    /**
     * Checks the numbers and copies the worlds.
     *
     * @param count       how many fake events
     * @param days        how many days back
     * @param worlds      the worlds
     * @param requestedBy who asked
     */
    public DevDataRequest {
        if (count < 1) {
            throw new IllegalArgumentException("at least one event: " + count);
        }
        if (days < 1) {
            throw new IllegalArgumentException("at least one day: " + days);
        }
        worlds = worlds.isEmpty() ? DevWorld.DEFAULTS : List.copyOf(worlds.subList(0, Math.min(MAX_WORLDS,
                worlds.size())));
    }
}
