package gg.moonrise.hawkeye.core.service.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DevDataRequestTest {

    @Test
    public void countAndDaysMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new DevDataRequest(0, 1, List.of(), "test"));
        assertThrows(IllegalArgumentException.class, () -> new DevDataRequest(1, 0, List.of(), "test"));
        assertEquals(1, new DevDataRequest(1, 1, List.of(), "test").count());
    }

    @Test
    public void noWorldsMeansTheVanillaOnes() {
        assertEquals(DevWorld.DEFAULTS, new DevDataRequest(10, 30, List.of(), "test").worlds());
    }

    @Test
    public void atMostEightWorldsAreKeptInOrder() {
        List<DevWorld> worlds = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            worlds.add(new DevWorld("world" + i, DevWorld.Dimension.OVERWORLD, 0, 64, 0, -64, 319));
        }

        List<DevWorld> kept = new DevDataRequest(10, 30, worlds, "test").worlds();

        assertEquals(DevDataRequest.MAX_WORLDS, kept.size());
        assertEquals(worlds.subList(0, DevDataRequest.MAX_WORLDS), kept);
    }

    @Test
    public void aWorldIsClampedIntoWhatAPositionHolds() {
        DevWorld world = new DevWorld("deep", DevWorld.Dimension.NETHER, 40_000_000, 5_000, -40_000_000, -4_000,
                4_000);

        assertEquals(BlockPositions.MIN_Y, world.minY());
        assertEquals(BlockPositions.MAX_Y, world.maxY());
        assertEquals(BlockPositions.MAX_Y, world.y());
        assertEquals(DevWorld.MAX_HORIZONTAL, world.x());
        assertEquals(-DevWorld.MAX_HORIZONTAL, world.z());
        assertEquals(0, new DevWorld("low", DevWorld.Dimension.END, 0, -10, 0, 0, 255).y());
        assertThrows(IllegalArgumentException.class, () -> new DevWorld("", DevWorld.Dimension.END, 0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DevWorld("x", DevWorld.Dimension.END, 0, 0, 0, 10, 1));
    }
}
