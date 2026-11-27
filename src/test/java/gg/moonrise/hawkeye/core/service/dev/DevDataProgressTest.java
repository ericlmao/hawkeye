package gg.moonrise.hawkeye.core.service.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

public class DevDataProgressTest {

    @Test
    public void percentRateAndRemaining() {
        DevDataProgress progress = new DevDataProgress("survival-dev", 1_000, 250, 8, 30, 5_000);

        assertEquals(25, progress.percent());
        assertEquals(50.0, progress.eventsPerSecond());
        assertEquals(OptionalLong.of(15_000), progress.remainingMillis());
    }

    @Test
    public void edgeCases() {
        DevDataProgress fresh = new DevDataProgress("survival-dev", 1_000, 0, 8, 30, 0);
        assertEquals(0, fresh.percent());
        assertEquals(0.0, fresh.eventsPerSecond());
        assertEquals(OptionalLong.empty(), fresh.remainingMillis(), "no estimate before the first events");

        DevDataProgress done = new DevDataProgress("survival-dev", 1_000, 1_000, 8, 30, 2_000);
        assertEquals(100, done.percent());
        assertEquals(OptionalLong.of(0), done.remainingMillis());
        assertEquals(100, new DevDataProgress("survival-dev", 0, 0, 8, 30, 0).percent());
    }
}
