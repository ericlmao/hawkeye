package gg.moonrise.hawkeye.core.service.dev;

import java.util.OptionalLong;
import org.jetbrains.annotations.NotNull;

/**
 * Where a run of fake data stands (or how it ended).
 *
 * @param node          the run's node, the server name its events carry ({@code survival-dev})
 * @param count         the events asked for
 * @param written       the events written so far
 * @param players       the fake players
 * @param days          how many days back the events reach
 * @param elapsedMillis how long the run has been going
 */
public record DevDataProgress(@NotNull String node, long count, long written, int players, int days,
        long elapsedMillis) {

    /**
     * How much of the asked-for events is written.
     *
     * @return 0 to 100; 100 when nothing was asked for
     */
    public int percent() {
        return count <= 0 ? 100 : (int) Math.min(100, written * 100 / count);
    }

    /**
     * How fast the run writes.
     *
     * @return events per second, 0 before any time passed
     */
    public double eventsPerSecond() {
        return elapsedMillis <= 0 ? 0 : written * 1000.0 / elapsedMillis;
    }

    /**
     * An estimate of the time left, at the rate so far.
     *
     * @return milliseconds, or empty before the first events are written
     */
    public @NotNull OptionalLong remainingMillis() {
        if (written <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0, elapsedMillis * (count - written) / written));
    }
}
