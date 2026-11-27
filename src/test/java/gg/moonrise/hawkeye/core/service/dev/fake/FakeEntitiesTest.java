package gg.moonrise.hawkeye.core.service.dev.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.List;
import java.util.SplittableRandom;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class FakeEntitiesTest {

    private static final List<String> NEVER = List.of("Pos", "Rotation", "UUID", "Motion", "Health");

    @Test
    public void aMobIsTheSmallestCompoundSummonLoads() {
        SplittableRandom random = new SplittableRandom(2);
        boolean named = false;
        boolean baby = false;
        for (int i = 0; i < 500; i++) {
            NbtTag.NbtCompound mob = read(FakeEntities.mob("minecraft:zombie", random));
            assertEquals(new NbtTag.NbtString("minecraft:zombie"), mob.get("id").orElseThrow());
            assertEquals(new NbtTag.NbtInt(FakeItems.DATA_VERSION), mob.get("DataVersion").orElseThrow());
            NEVER.forEach(key -> assertFalse(mob.get(key).isPresent(), key));
            if (mob.get("CustomName").orElse(null) instanceof NbtTag.NbtString name) {
                GsonComponentSerializer.gson().deserialize(name.value());
                assertEquals(new NbtTag.NbtByte((byte) 1), mob.get("PersistenceRequired").orElseThrow());
                named = true;
            }
            baby |= mob.get("IsBaby").isPresent();
        }
        assertTrue(named && baby, named + " " + baby);
    }

    @Test
    public void onlyZombiesAreBabies() {
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 200; i++) {
            assertFalse(read(FakeEntities.mob("minecraft:cow", random)).get("IsBaby").isPresent());
        }
    }

    @Test
    public void anObjectThatDoesNotHangHasItsTypeAndDataVersionOnly() {
        NbtTag.NbtCompound cart = read(FakeEntities.object("minecraft:minecart", 4, 70, -9, new SplittableRandom(5)));

        assertEquals(List.of("id", "DataVersion"), List.copyOf(cart.values().keySet()));
        assertEquals(new NbtTag.NbtString("minecraft:minecart"), cart.get("id").orElseThrow());
    }

    @Test
    public void anItemFrameHangsAtTheEventsBlock() {
        SplittableRandom random = new SplittableRandom(5);
        for (String id : List.of("minecraft:item_frame", "minecraft:glow_item_frame")) {
            NbtTag.NbtCompound frame = read(FakeEntities.object(id, 4, 70, -9, random));

            assertEquals(List.of("id", "Pos", "TileX", "TileY", "TileZ", "Facing", "DataVersion"),
                    List.copyOf(frame.values().keySet()));
            assertEquals(NbtTag.NbtList.of(NbtTag.DOUBLE, NbtTag.NbtDouble.of(4.5), NbtTag.NbtDouble.of(70.5),
                    NbtTag.NbtDouble.of(-8.5)), frame.get("Pos").orElseThrow());
            assertEquals(new NbtTag.NbtInt(4), frame.get("TileX").orElseThrow());
            assertEquals(new NbtTag.NbtInt(70), frame.get("TileY").orElseThrow());
            assertEquals(new NbtTag.NbtInt(-9), frame.get("TileZ").orElseThrow());
            byte facing = ((NbtTag.NbtByte) frame.get("Facing").orElseThrow()).value();
            assertTrue(facing >= 2 && facing <= 5, Byte.toString(facing));
        }
    }

    @Test
    public void aPaintingHangsAtTheEventsBlockWithItsPicture() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 50; i++) {
            NbtTag.NbtCompound painting = read(FakeEntities.object("minecraft:painting", -30, -60, 12, random));

            assertEquals(List.of("id", "Pos", "TileX", "TileY", "TileZ", "facing", "variant", "DataVersion"),
                    List.copyOf(painting.values().keySet()));
            assertEquals(new NbtTag.NbtInt(-30), painting.get("TileX").orElseThrow());
            assertEquals(new NbtTag.NbtInt(-60), painting.get("TileY").orElseThrow());
            assertEquals(new NbtTag.NbtInt(12), painting.get("TileZ").orElseThrow());
            byte facing = ((NbtTag.NbtByte) painting.get("facing").orElseThrow()).value();
            assertTrue(facing >= 0 && facing <= 3, Byte.toString(facing));
            String variant = ((NbtTag.NbtString) painting.get("variant").orElseThrow()).value();
            assertTrue(variant.startsWith("minecraft:"), variant);
        }
    }

    private static @NotNull NbtTag.NbtCompound read(byte @NotNull [] bytes) {
        return NbtRoot.read(bytes).compound();
    }
}
