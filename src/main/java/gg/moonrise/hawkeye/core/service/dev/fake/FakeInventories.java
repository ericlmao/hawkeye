package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.serialize.ItemSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * Fake item stacks and inventories: a broken container's contents, a player's inventory for snapshots, and
 * the single items of item and container events. Items in {@link FakeContent#WITH_COMPONENTS} always carry
 * components (enchantments, damage, a potion, contents or a book); a few plain ones carry a name.
 */
public final class FakeInventories {

    /** The slots of a chest. */
    public static final int CHEST_SLOTS = 27;

    /** The storage slots of a player's inventory (hotbar included). */
    public static final int STORAGE_SLOTS = 36;

    /** The first armour slot of {@code PlayerInventory#getContents()} (boots to helmet: 36 to 39). */
    public static final int ARMOUR = 36;

    /** The off-hand slot. */
    public static final int OFF_HAND = 40;

    private static final String[] ARMOUR_PIECES = {"minecraft:iron_boots", "minecraft:iron_leggings",
        "minecraft:diamond_chestplate", "minecraft:iron_helmet"};

    private FakeInventories() {
    }

    /**
     * The contents of a container block: 1 to 27 full or partial stacks, about a quarter with components.
     *
     * @param random the random source
     * @return the non-empty slots, by index
     */
    public static @NotNull List<Payload.Slot> container(@NotNull SplittableRandom random) {
        int wanted = 1 + random.nextInt(CHEST_SLOTS);
        List<Payload.Slot> slots = new ArrayList<>(wanted);
        for (int index = 0; index < CHEST_SLOTS && wanted > 0; index++) {
            // Each remaining slot is taken with the chance that leaves exactly `wanted` slots at the end.
            if (random.nextInt(CHEST_SLOTS - index) < wanted) {
                slots.add(new Payload.Slot(index, randomStack(random)));
                wanted--;
            }
        }
        return slots;
    }

    /**
     * A player's whole inventory: 5 to 25 storage stacks, some armour, rarely the off hand and the cursor.
     *
     * @param random the random source
     * @return the non-empty slots, by index ({@link Payload.Snapshot#CURSOR} for the cursor)
     */
    public static @NotNull List<Payload.Slot> player(@NotNull SplittableRandom random) {
        int wanted = 5 + random.nextInt(21);
        List<Payload.Slot> slots = new ArrayList<>(wanted + 6);
        for (int index = 0; index < STORAGE_SLOTS && wanted > 0; index++) {
            if (random.nextInt(STORAGE_SLOTS - index) < wanted) {
                slots.add(new Payload.Slot(index, randomStack(random)));
                wanted--;
            }
        }
        for (int piece = 0; piece < ARMOUR_PIECES.length; piece++) {
            if (random.nextBoolean()) {
                slots.add(new Payload.Slot(ARMOUR + piece, FakeItems.plain(ARMOUR_PIECES[piece], 1)));
            }
        }
        if (random.nextInt(10) == 0) {
            slots.add(new Payload.Slot(OFF_HAND, FakeItems.plain("minecraft:shield", 1)));
        }
        if (random.nextInt(20) == 0) {
            slots.add(new Payload.Slot(Payload.Snapshot.CURSOR, FakeItems.plain("minecraft:cobblestone",
                    1 + random.nextInt(64))));
        }
        return slots;
    }

    /**
     * A player's inventory a little later: 0 to 3 slots changed (a stack taken, a new one, a stack replaced).
     *
     * @param slots  the inventory before
     * @param random the random source
     * @return the inventory after, by index
     */
    public static @NotNull List<Payload.Slot> mutate(@NotNull List<Payload.Slot> slots,
            @NotNull SplittableRandom random) {
        TreeMap<Integer, byte[]> byIndex = new TreeMap<>();
        slots.forEach(slot -> byIndex.put(slot.index(), slot.item()));
        int changes = random.nextInt(4);
        for (int i = 0; i < changes; i++) {
            int index = random.nextInt(STORAGE_SLOTS);
            switch (random.nextInt(3)) {
                case 0 -> {
                    if (byIndex.size() > 1) {
                        byIndex.remove(index);
                    }
                }
                case 1 -> byIndex.putIfAbsent(index, randomStack(random));
                default -> byIndex.put(index, randomStack(random));
            }
        }
        List<Payload.Slot> out = new ArrayList<>(byIndex.size());
        byIndex.forEach((index, item) -> out.add(new Payload.Slot(index, item)));
        return out;
    }

    /**
     * How many items an inventory holds, the amount of a snapshot event.
     *
     * @param slots the slots
     * @return the sum of their stack sizes
     */
    public static int count(@NotNull List<Payload.Slot> slots) {
        int total = 0;
        for (Payload.Slot slot : slots) {
            total += ItemSummary.of(slot.item()).map(ItemSummary::count).orElse(1);
        }
        return total;
    }

    /**
     * The payload bytes of one item of an item or container event: present only when the item has
     * components (always for {@link FakeContent#WITH_COMPONENTS}, now and then a name otherwise), with count 1.
     *
     * @param id     the namespaced material
     * @param random the random source
     * @return the item's NBT, or empty for a plain item
     */
    public static @NotNull Optional<byte[]> single(@NotNull String id, @NotNull SplittableRandom random) {
        if (FakeContent.WITH_COMPONENTS.contains(id)) {
            return Optional.of(withComponents(id, random));
        }
        if (random.nextInt(100) < 3) {
            return Optional.of(FakeItems.named(id, 1, FakeContent.words(random, 1 + random.nextInt(3))));
        }
        return Optional.empty();
    }

    /**
     * One item of a kind that always carries components (see {@link FakeContent#COMPONENT_ITEMS}), count 1.
     *
     * @param id     the namespaced material
     * @param random the random source
     * @return the item's NBT
     */
    public static byte @NotNull [] withComponents(@NotNull String id, @NotNull SplittableRandom random) {
        return switch (id) {
            case "minecraft:enchanted_book" -> FakeItems.enchantedBook(enchantments(id, random, 1));
            case "minecraft:potion", "minecraft:splash_potion" -> FakeItems.potion(id,
                    FakeContent.POTIONS.get(random.nextInt(FakeContent.POTIONS.size())));
            case "minecraft:shulker_box" -> FakeItems.shulkerBox(boxContents(random));
            case "minecraft:written_book" -> FakeItems.writtenBook(FakeContent.words(random, 2),
                    FakeServer.player(random.nextInt(FakeServer.MIN_PLAYERS)).name(),
                    List.of(FakeContent.words(random, 10 + random.nextInt(60)),
                            FakeContent.words(random, 5 + random.nextInt(60))));
            default -> {
                Map<String, Integer> enchantments = random.nextInt(4) == 0 ? Map.of() : enchantments(id, random, 0);
                int damage = enchantments.isEmpty() ? 1 + random.nextInt(400)
                        : random.nextBoolean() ? 0 : random.nextInt(400);
                yield FakeItems.tool(id, enchantments, damage);
            }
        };
    }

    /**
     * A worn-out tool or armour piece, as an item break stores it: damaged, sometimes enchanted.
     *
     * @param id     the namespaced material
     * @param random the random source
     * @return the item's NBT
     */
    public static byte @NotNull [] wornOut(@NotNull String id, @NotNull SplittableRandom random) {
        Map<String, Integer> enchantments = random.nextInt(3) == 0 ? enchantments(id, random, 0) : Map.of();
        return FakeItems.tool(id, enchantments, 50 + random.nextInt(1_500));
    }

    /** A stack for a container or an inventory slot, at its real size. */
    private static byte @NotNull [] randomStack(@NotNull SplittableRandom random) {
        if (random.nextInt(4) == 0) {
            return withComponents(FakeContent.COMPONENT_ITEMS.pick(random), random);
        }
        String id = FakeContent.ITEMS.pick(random);
        if (FakeContent.WITH_COMPONENTS.contains(id)) {
            return withComponents(id, random);
        }
        int count = random.nextInt(5) < 3 ? 64 : 1 + random.nextInt(64);
        return random.nextInt(50) == 0 ? FakeItems.named(id, count, FakeContent.words(random, 2))
                : FakeItems.plain(id, count);
    }

    /** One to three enchantments that suit the item (at least {@code min}). */
    private static @NotNull Map<String, Integer> enchantments(@NotNull String id, @NotNull SplittableRandom random,
            int min) {
        List<String> suitable = suitable(id);
        int wanted = Math.max(min, 1 + random.nextInt(Math.min(3, suitable.size())));
        Map<String, Integer> out = new LinkedHashMap<>();
        while (out.size() < wanted) {
            String enchantment = suitable.get(random.nextInt(suitable.size()));
            boolean single = enchantment.equals("minecraft:mending") || enchantment.equals("minecraft:silk_touch");
            out.putIfAbsent(enchantment, single ? 1 : 1 + random.nextInt(4));
        }
        return out;
    }

    private static @NotNull List<String> suitable(@NotNull String id) {
        List<String> all = FakeContent.ENCHANTMENTS;
        if (id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_axe")) {
            return List.of(all.get(0), all.get(1), all.get(2), all.get(3), all.get(4));
        }
        if (id.endsWith("_sword") || id.endsWith("trident")) {
            return List.of(all.get(5), all.get(6), all.get(1), all.get(4));
        }
        if (id.endsWith("bow")) {
            return List.of(all.get(8), all.get(1), all.get(4));
        }
        if (id.endsWith("_chestplate") || id.endsWith("_boots") || id.endsWith("_helmet")
                || id.endsWith("_leggings") || id.endsWith("enchanted_book")) {
            return List.of(all.get(7), all.get(1), all.get(4));
        }
        return List.of(all.get(1), all.get(4));
    }

    private static @NotNull List<FakeItems.Content> boxContents(@NotNull SplittableRandom random) {
        int wanted = 1 + random.nextInt(8);
        List<FakeItems.Content> contents = new ArrayList<>(wanted);
        for (int slot = 0; slot < CHEST_SLOTS && wanted > 0; slot++) {
            if (random.nextInt(CHEST_SLOTS - slot) < wanted) {
                String id = FakeContent.ITEMS.pick(random);
                int count = FakeContent.WITH_COMPONENTS.contains(id) ? 1 : 1 + random.nextInt(64);
                contents.add(new FakeItems.Content(slot, id, count));
                wanted--;
            }
        }
        return contents;
    }
}
