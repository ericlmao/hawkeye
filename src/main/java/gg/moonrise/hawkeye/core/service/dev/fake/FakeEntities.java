package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.jetbrains.annotations.NotNull;

/**
 * Fake entities as raw NBT: the smallest compound {@code /summon} loads, {@code id} and {@code DataVersion}
 * ({@link FakeItems#DATA_VERSION}), now and then with a name or as a baby. Position, rotation, UUID, motion and
 * health are never written: a rollback takes the place from the event row, and Minecraft loads every missing
 * tag as its default.
 */
public final class FakeEntities {

    private FakeEntities() {
    }

    /**
     * A killed mob.
     *
     * @param id     the namespaced entity type
     * @param random the random source
     * @return the entity's NBT; about one in ten is named and persistent, a zombie is sometimes a baby
     */
    public static byte @NotNull [] mob(@NotNull String id, @NotNull SplittableRandom random) {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("id", new NbtTag.NbtString(id));
        if (random.nextInt(10) == 0) {
            root.put("CustomName", new NbtTag.NbtString(FakeItems.text(FakeContent.petName(random))));
            root.put("PersistenceRequired", new NbtTag.NbtByte((byte) 1));
        }
        if (id.equals("minecraft:zombie") && random.nextInt(5) == 0) {
            root.put("IsBaby", new NbtTag.NbtByte((byte) 1));
        }
        root.put("DataVersion", new NbtTag.NbtInt(FakeItems.DATA_VERSION));
        return new NbtRoot("", new NbtTag.NbtCompound(root)).write();
    }

    /**
     * A removed object (a painting, an item frame, a minecart) or a mob caught in a bucket.
     *
     * @param id the namespaced entity type
     * @return the entity's NBT
     */
    public static byte @NotNull [] object(@NotNull String id) {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("id", new NbtTag.NbtString(id));
        root.put("DataVersion", new NbtTag.NbtInt(FakeItems.DATA_VERSION));
        return new NbtRoot("", new NbtTag.NbtCompound(root)).write();
    }
}
