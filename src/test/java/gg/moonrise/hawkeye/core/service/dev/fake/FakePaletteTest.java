package gg.moonrise.hawkeye.core.service.dev.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

public class FakePaletteTest {

    private static final FakePalette PALETTE = FakePalette.builder()
            .add("stone", 50).add("oak_log", 10, "axis=x|y|z").add("oak_door", 1, "half=lower|upper", "open=false|true")
            .build();

    @Test
    public void everyVariantIsAValue() {
        assertEquals(1 + 3 + 4, PALETTE.distinct());
        assertEquals(List.of("minecraft:stone", "minecraft:oak_log[axis=x]", "minecraft:oak_log[axis=y]",
                "minecraft:oak_log[axis=z]", "minecraft:oak_door[half=lower,open=false]",
                "minecraft:oak_door[half=lower,open=true]", "minecraft:oak_door[half=upper,open=false]",
                "minecraft:oak_door[half=upper,open=true]"), PALETTE.values());
    }

    @Test
    public void picksAreDeterministicAndReachEveryValue() {
        List<String> first = picks(9);
        assertEquals(first, picks(9));
        Set<String> seen = new HashSet<>(first);
        assertEquals(Set.copyOf(PALETTE.values()), seen);
        long stone = first.stream().filter("minecraft:stone"::equals).count();
        assertTrue(stone > first.size() / 2, "the weights count: " + stone);
    }

    @Test
    public void everyContentPaletteHasValues() {
        for (FakePalette palette : List.of(FakeContent.OVERWORLD, FakeContent.NETHER, FakeContent.END,
                FakeContent.GROWN, FakeContent.ITEMS, FakeContent.COMPONENT_ITEMS, FakeContent.THROWN,
                FakeContent.WORN_OUT, FakeContent.TRADED, FakeContent.KILLED, FakeContent.PLACED_ENTITIES,
                FakeContent.REMOVED_ENTITIES, FakeContent.HANDLED_ENTITIES)) {
            assertTrue(palette.distinct() > 0);
            palette.values().forEach(value -> assertTrue(value.matches("minecraft:[a-z_]+(\\[[a-z0-9_=,]+])?"), value));
            assertTrue(palette.values().stream().noneMatch(value -> value.contains("boat")
                    || value.startsWith("minecraft:chain")), "ids every supported server knows");
        }
        assertThrows(IllegalStateException.class, () -> FakePalette.builder().build());
    }

    @Test
    public void interactionsToggleOneProperty() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 200; i++) {
            FakeContent.Change change = FakeContent.interaction(random);
            String after = change.after();
            String before = change.before();
            boolean open = after.contains("open=");
            String property = open ? "open" : "powered";
            assertEquals(before.replace(property + "=true", property + "=false"),
                    after.replace(property + "=true", property + "=false"), after + " / " + before);
            assertTrue(!after.equals(before));
        }
    }

    private static List<String> picks(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            out.add(PALETTE.pick(random));
        }
        return out;
    }
}
