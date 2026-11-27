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
    public void anObjectHasItsTypeAndDataVersionOnly() {
        NbtTag.NbtCompound frame = read(FakeEntities.object("minecraft:item_frame"));

        assertEquals(List.of("id", "DataVersion"), List.copyOf(frame.values().keySet()));
        assertEquals(new NbtTag.NbtString("minecraft:item_frame"), frame.get("id").orElseThrow());
    }

    private static @NotNull NbtTag.NbtCompound read(byte @NotNull [] bytes) {
        return NbtRoot.read(bytes).compound();
    }
}
