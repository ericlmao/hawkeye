package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.jetbrains.annotations.NotNull;

/**
 * Fake entities as raw NBT: the smallest compound {@code /summon} loads, {@code id} and {@code DataVersion}
 * ({@link FakeItems#DATA_VERSION}), now and then with a name or as a baby. UUID, motion and health are never
 * written, and neither are position and rotation, except for an entity that hangs on a block: a rollback takes
 * the place from the event row, and Minecraft loads every missing tag as its default. A painting or an item
 * frame is saved with the block it hangs at and the way it faces (a real snapshot keeps them too), without
 * which the server logs "Block-attached entity at invalid position" when a rollback loads it.
 */
public final class FakeEntities {

    private static final String PAINTING = "minecraft:painting";

    /** Entities that hang on a block. */
    private static final Set<String> HANGING = Set.of("minecraft:item_frame", "minecraft:glow_item_frame", PAINTING);

    /** One-block paintings every supported version has. */
    private static final List<String> PAINTINGS = List.of("minecraft:kebab", "minecraft:aztec", "minecraft:alban",
            "minecraft:aztec2", "minecraft:bomb", "minecraft:plant", "minecraft:wasteland");

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
     * @param id     the namespaced entity type
     * @param x      the event's block x
     * @param y      the event's block y
     * @param z      the event's block z
     * @param random the random source
     * @return the entity's NBT; a painting or an item frame hangs at the event's block
     */
    public static byte @NotNull [] object(@NotNull String id, int x, int y, int z, @NotNull SplittableRandom random) {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("id", new NbtTag.NbtString(id));
        if (HANGING.contains(id)) {
            hang(root, id, x, y, z, random);
        }
        root.put("DataVersion", new NbtTag.NbtInt(FakeItems.DATA_VERSION));
        return new NbtRoot("", new NbtTag.NbtCompound(root)).write();
    }

    /** The 1.21.1 tags of a hanging entity: where it is, the block it hangs at and the way it faces. */
    private static void hang(@NotNull Map<String, NbtTag> root, @NotNull String id, int x, int y, int z,
            @NotNull SplittableRandom random) {
        root.put("Pos", NbtTag.NbtList.of(NbtTag.DOUBLE, NbtTag.NbtDouble.of(x + 0.5), NbtTag.NbtDouble.of(y + 0.5),
                NbtTag.NbtDouble.of(z + 0.5)));
        root.put("TileX", new NbtTag.NbtInt(x));
        root.put("TileY", new NbtTag.NbtInt(y));
        root.put("TileZ", new NbtTag.NbtInt(z));
        if (id.equals(PAINTING)) {
            // A painting faces one of the four sides (0 to 3) and names its picture.
            root.put("facing", new NbtTag.NbtByte((byte) random.nextInt(4)));
            root.put("variant", new NbtTag.NbtString(PAINTINGS.get(random.nextInt(PAINTINGS.size()))));
        } else {
            // An item frame faces any of the six sides; 2 to 5 are the walls.
            root.put("Facing", new NbtTag.NbtByte((byte) (2 + random.nextInt(4))));
        }
    }
}
