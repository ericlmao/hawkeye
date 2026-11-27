package gg.moonrise.hawkeye.core.service.dev.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.serialize.ItemDetails;
import gg.moonrise.hawkeye.util.serialize.ItemSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class FakeItemsTest {

    @Test
    public void aPlainStackHasNoComponents() {
        byte[] bytes = FakeItems.plain("minecraft:cobblestone", 64);

        assertEquals(new ItemSummary("minecraft:cobblestone", 64), ItemSummary.of(bytes).orElseThrow());
        NbtTag.NbtCompound root = NbtRoot.read(bytes).compound();
        assertEquals(List.of("id", "count", "DataVersion"), List.copyOf(root.values().keySet()));
        assertEquals(new NbtTag.NbtInt(FakeItems.DATA_VERSION), root.get("DataVersion").orElseThrow());
        assertTrue(ItemDetails.of(bytes).orElseThrow().isEmpty());
    }

    @Test
    public void aNamedStackShowsItsName() {
        byte[] bytes = FakeItems.named("minecraft:diamond", 3, "the loot");

        assertEquals(new ItemSummary("minecraft:diamond", 3), ItemSummary.of(bytes).orElseThrow());
        assertEquals("the loot", plain(ItemDetails.of(bytes).orElseThrow().name().orElseThrow()));
        assertEquals(List.of("id", "count", "components", "DataVersion"),
                List.copyOf(NbtRoot.read(bytes).compound().values().keySet()));
    }

    @Test
    public void aToolKeepsItsEnchantmentsAndDamage() {
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        enchantments.put("minecraft:efficiency", 4);
        enchantments.put("minecraft:mending", 1);
        byte[] bytes = FakeItems.tool("minecraft:diamond_pickaxe", enchantments, 120);

        ItemDetails details = ItemDetails.of(bytes).orElseThrow();
        assertEquals(List.of(new ItemDetails.Enchantment("minecraft:efficiency", 4),
                new ItemDetails.Enchantment("minecraft:mending", 1)), details.enchantments());
        NbtTag.NbtCompound components = NbtRoot.read(bytes).compound().compound("components").orElseThrow();
        assertEquals(new NbtTag.NbtInt(120), components.get("minecraft:damage").orElseThrow());
        assertEquals(1, ItemSummary.of(bytes).orElseThrow().count());
        assertThrows(IllegalArgumentException.class, () -> FakeItems.tool("minecraft:bow", Map.of(), 0));
    }

    @Test
    public void anEnchantedBookStoresItsEnchantments() {
        byte[] bytes = FakeItems.enchantedBook(Map.of("minecraft:sharpness", 5));

        assertEquals(List.of(new ItemDetails.Enchantment("minecraft:sharpness", 5)),
                ItemDetails.of(bytes).orElseThrow().enchantments());
        assertEquals("minecraft:enchanted_book", ItemSummary.of(bytes).orElseThrow().id());
    }

    @Test
    public void aPotionNamesItsType() {
        byte[] bytes = FakeItems.potion("minecraft:splash_potion", "minecraft:swiftness");

        NbtTag.NbtCompound contents = NbtRoot.read(bytes).compound().compound("components").orElseThrow()
                .compound("minecraft:potion_contents").orElseThrow();
        assertEquals(new NbtTag.NbtString("minecraft:swiftness"), contents.get("potion").orElseThrow());
        assertEquals("minecraft:splash_potion", ItemSummary.of(bytes).orElseThrow().id());
    }

    @Test
    public void aWrittenBookReadsBack() {
        byte[] bytes = FakeItems.writtenBook("my base", "SteveCraft", List.of("page one", "page two"));

        ItemDetails.BookContent book = ItemDetails.of(bytes).orElseThrow().book().orElseThrow();
        assertEquals("my base", book.title());
        assertEquals("SteveCraft", book.author());
        assertEquals(List.of("page one", "page two"), book.pages().stream().map(FakeItemsTest::plain).toList());
        assertTrue(book.signed());
    }

    @Test
    public void aShulkerBoxHoldsItsContents() {
        byte[] bytes = FakeItems.shulkerBox(List.of(new FakeItems.Content(0, "minecraft:dirt", 64),
                new FakeItems.Content(5, "minecraft:torch", 12)));

        NbtTag.NbtCompound components = NbtRoot.read(bytes).compound().compound("components").orElseThrow();
        NbtTag.NbtList slots = (NbtTag.NbtList) components.get("minecraft:container").orElseThrow();
        assertEquals(2, slots.values().size());
        NbtTag.NbtCompound second = (NbtTag.NbtCompound) slots.values().get(1);
        assertEquals(new NbtTag.NbtInt(5), second.get("slot").orElseThrow());
        assertEquals(new NbtTag.NbtString("minecraft:torch"),
                second.compound("item").orElseThrow().get("id").orElseThrow());
    }

    @Test
    public void textIsAJsonComponent() {
        assertEquals("{\"text\":\"a \\\"quoted\\\" word\"}", FakeItems.text("a \"quoted\" word"));
    }

    @Test
    public void everyItemWithComponentsReadsBack() {
        SplittableRandom random = new SplittableRandom(4);
        for (String id : FakeContent.WITH_COMPONENTS) {
            for (int i = 0; i < 20; i++) {
                byte[] bytes = FakeInventories.withComponents(id, random);
                assertEquals(new ItemSummary(id, 1), ItemSummary.of(bytes).orElseThrow(), id);
                assertFalse(NbtRoot.read(bytes).compound().compound("components").orElseThrow().values().isEmpty(), id);
            }
        }
    }

    private static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
