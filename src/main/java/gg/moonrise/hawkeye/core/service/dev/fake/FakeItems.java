package gg.moonrise.hawkeye.core.service.dev.fake;

import com.google.gson.JsonObject;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Fake items as the raw NBT {@code ItemStack#serializeAsBytes} stores ({@code util.serialize.ItemBytes}): a
 * root holding {@code id}, {@code count}, the data {@code components} when there are any, and
 * {@code DataVersion}. Every component has the shape of Minecraft 1.21.1, whose data version every item carries
 * ({@link #DATA_VERSION}).
 */
public final class FakeItems {

    /**
     * Minecraft 1.21.1, the oldest supported server: every supported server's data fixers upgrade an item
     * stamped with it, so the fake items deserialize on each of them.
     */
    public static final int DATA_VERSION = 3955;

    private static final String CUSTOM_NAME = "minecraft:custom_name";
    private static final String ENCHANTMENTS = "minecraft:enchantments";
    private static final String STORED_ENCHANTMENTS = "minecraft:stored_enchantments";
    private static final String DAMAGE = "minecraft:damage";
    private static final String POTION_CONTENTS = "minecraft:potion_contents";
    private static final String WRITTEN_BOOK = "minecraft:written_book_content";
    private static final String CONTAINER = "minecraft:container";

    private FakeItems() {
    }

    /**
     * A stack without components.
     *
     * @param id    the namespaced material
     * @param count the stack size
     * @return the item's NBT
     */
    public static byte @NotNull [] plain(@NotNull String id, int count) {
        return write(id, count, Map.of());
    }

    /**
     * A stack with a custom name.
     *
     * @param id    the namespaced material
     * @param count the stack size
     * @param name  the name, as plain text
     * @return the item's NBT
     */
    public static byte @NotNull [] named(@NotNull String id, int count, @NotNull String name) {
        return write(id, count, Map.of(CUSTOM_NAME, new NbtTag.NbtString(text(name))));
    }

    /**
     * A tool, weapon or armour piece (one item) with enchantments, damage or both.
     *
     * @param id           the namespaced material
     * @param enchantments enchantment id to level, in the order to store them
     * @param damage       the damage taken, 0 for none
     * @return the item's NBT
     * @throws IllegalArgumentException without enchantments and damage (that is a plain item)
     */
    public static byte @NotNull [] tool(@NotNull String id, @NotNull Map<String, Integer> enchantments, int damage) {
        if (enchantments.isEmpty() && damage <= 0) {
            throw new IllegalArgumentException("a tool needs enchantments or damage; use plain instead");
        }
        Map<String, NbtTag> components = new LinkedHashMap<>();
        if (!enchantments.isEmpty()) {
            components.put(ENCHANTMENTS, levels(enchantments));
        }
        if (damage > 0) {
            components.put(DAMAGE, new NbtTag.NbtInt(damage));
        }
        return write(id, 1, components);
    }

    /**
     * An enchanted book.
     *
     * @param enchantments the stored enchantments, id to level
     * @return the item's NBT
     */
    public static byte @NotNull [] enchantedBook(@NotNull Map<String, Integer> enchantments) {
        return write("minecraft:enchanted_book", 1, Map.of(STORED_ENCHANTMENTS, levels(enchantments)));
    }

    /**
     * A potion (a drinkable, splash or lingering one).
     *
     * @param id       the namespaced material ({@code minecraft:potion}, {@code minecraft:splash_potion})
     * @param potionId the namespaced potion type
     * @return the item's NBT
     */
    public static byte @NotNull [] potion(@NotNull String id, @NotNull String potionId) {
        return write(id, 1, Map.of(POTION_CONTENTS, new NbtTag.NbtCompound(Map.of("potion",
                new NbtTag.NbtString(potionId)))));
    }

    /**
     * A signed book.
     *
     * @param title  the title
     * @param author the author
     * @param pages  the pages, as plain text
     * @return the item's NBT
     */
    public static byte @NotNull [] writtenBook(@NotNull String title, @NotNull String author,
            @NotNull List<String> pages) {
        List<NbtTag> stored = new ArrayList<>(pages.size());
        for (String page : pages) {
            stored.add(new NbtTag.NbtCompound(Map.of("raw", new NbtTag.NbtString(text(page)))));
        }
        Map<String, NbtTag> content = new LinkedHashMap<>();
        content.put("pages", new NbtTag.NbtList(NbtTag.COMPOUND, stored));
        content.put("title", new NbtTag.NbtCompound(Map.of("raw", new NbtTag.NbtString(title))));
        content.put("author", new NbtTag.NbtString(author));
        return write("minecraft:written_book", 1, Map.of(WRITTEN_BOOK, new NbtTag.NbtCompound(content)));
    }

    /**
     * A shulker box with items inside.
     *
     * @param contents the items inside, by slot
     * @return the item's NBT
     */
    public static byte @NotNull [] shulkerBox(@NotNull List<Content> contents) {
        List<NbtTag> slots = new ArrayList<>(contents.size());
        for (Content content : contents) {
            Map<String, NbtTag> item = new LinkedHashMap<>();
            item.put("id", new NbtTag.NbtString(content.id()));
            item.put("count", new NbtTag.NbtInt(content.count()));
            Map<String, NbtTag> slot = new LinkedHashMap<>();
            slot.put("slot", new NbtTag.NbtInt(content.slot()));
            slot.put("item", new NbtTag.NbtCompound(item));
            slots.add(new NbtTag.NbtCompound(slot));
        }
        return write("minecraft:shulker_box", 1, Map.of(CONTAINER, new NbtTag.NbtList(NbtTag.COMPOUND, slots)));
    }

    /**
     * Plain text as the JSON text component Minecraft 1.21.1 stores in names, signs and book pages.
     *
     * @param plain the text
     * @return {@code {"text":"..."}}
     */
    public static @NotNull String text(@NotNull String plain) {
        JsonObject component = new JsonObject();
        component.addProperty("text", plain);
        return component.toString();
    }

    private static @NotNull NbtTag levels(@NotNull Map<String, Integer> enchantments) {
        Map<String, NbtTag> levels = new LinkedHashMap<>();
        enchantments.forEach((enchantment, level) -> levels.put(enchantment, new NbtTag.NbtInt(level)));
        return new NbtTag.NbtCompound(Map.of("levels", new NbtTag.NbtCompound(levels)));
    }

    private static byte @NotNull [] write(@NotNull String id, int count, @NotNull Map<String, NbtTag> components) {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("id", new NbtTag.NbtString(id));
        root.put("count", new NbtTag.NbtInt(count));
        if (!components.isEmpty()) {
            root.put("components", new NbtTag.NbtCompound(components));
        }
        root.put("DataVersion", new NbtTag.NbtInt(DATA_VERSION));
        return new NbtRoot("", new NbtTag.NbtCompound(root)).write();
    }

    /**
     * An item inside a shulker box.
     *
     * @param slot  the box's slot, 0 to 26
     * @param id    the namespaced material
     * @param count the stack size
     */
    public record Content(int slot, @NotNull String id, int count) {
    }
}
