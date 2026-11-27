package gg.moonrise.hawkeye.listener.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.inspect.InspectorService;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Inspector clicks on a mocked server, through the registered listener. */
public class InspectorListenerTest {

    private ServerMock server;
    private World world;
    private PlayerMock alex;
    private EventRecorder recorder;
    private InspectorService inspector;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        TestPlugins.awaitStorage();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        alex.setOp(true);
        alex.setLocation(new Location(world, 0, 64, 0));
        TestPlugins.drain(alex);
        recorder = TestPlugins.bean(EventRecorder.class);
        inspector = TestPlugins.bean(InspectorService.class);
        inspector.toggle(alex.getUniqueId());
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void leftClickShowsTheBlockHistoryWithoutBreakingIt() {
        Block block = block(5, 64, 5, Material.STONE);
        placeAndBreak(5, 64, 5);
        placeAndBreak(6, 64, 5);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals(3, reply.size(), reply.toString());
        assertEquals("----- Hawkeye 5 64 5 -----", reply.getFirst());
        assertTrue(reply.get(1).contains("#fire broke stone"), reply.get(1));
        assertTrue(reply.get(2).contains("Alex placed stone"), reply.get(2));
    }

    @Test
    public void rightClickOnAContainerShowsItsTransactions() {
        Block chest = block(1, 64, 1, Material.CHEST);
        recorder.record(Action.BLOCK_PLACE, Actors.of(alex), world, 1, 64, 1, "minecraft:chest", "minecraft:air", 0,
                null);
        recorder.record(Action.CONTAINER_DEPOSIT, Actors.of(alex), world, 1, 64, 1, "minecraft:diamond", null, 5,
                null);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, chest);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals(2, reply.size(), reply.toString());
        assertTrue(reply.get(1).contains("Alex added 5x diamond"), reply.get(1));
    }

    @Test
    public void rightClickOnABlockThatHoldsItemsShowsItsTransactions() {
        List<Material> types = List.of(Material.CHISELED_BOOKSHELF, Material.JUKEBOX);
        for (int x = 0; x < types.size(); x++) {
            Material type = types.get(x);
            Block block = block(x, 70, 3, type);
            recorder.record(Action.CONTAINER_WITHDRAW, Actors.of(alex), world, x, 70, 3, "minecraft:book", null, 1,
                    null);

            PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block);

            assertTrue(cancelled(click), type.name());
            List<String> reply = AwaitReplies.reply(server, alex);
            assertEquals("----- Hawkeye " + x + " 70 3 -----", reply.getFirst(), type.name());
            assertTrue(reply.stream().anyMatch(line -> line.contains("Alex removed 1x book")), reply.toString());
        }
    }

    @Test
    public void rightClickElsewhereShowsTheBlockOnTheClickedFace() {
        Block block = block(5, 64, 5, Material.STONE);
        placeAndBreak(5, 65, 5);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 5 65 5 -----", reply.getFirst());
        assertEquals(3, reply.size(), reply.toString());
    }

    @Test
    public void rightClickWithABlockShowsTheSpotItWouldFill() {
        Block block = block(5, 64, 5, Material.STONE);
        placeAndBreak(5, 65, 5);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.DIRT), block);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 5 65 5 -----", reply.getFirst());
    }

    @Test
    public void rightClickWithABlockOnAReplaceableBlockShowsThatBlock() {
        Block grass = block(7, 64, 7, Material.SHORT_GRASS);
        placeAndBreak(7, 64, 7);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.DIRT), grass);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 7 64 7 -----", reply.getFirst());
    }

    @Test
    public void rightClickWithAnEmptyHandOnAReplaceableBlockShowsTheBlockAbove() {
        Block grass = block(7, 64, 7, Material.SHORT_GRASS);
        placeAndBreak(7, 65, 7);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, grass);

        assertTrue(cancelled(click));
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 7 65 7 -----", reply.getFirst());
    }

    @Test
    public void placingShowsThatSpotInsteadOfPlacing() {
        Block against = block(5, 64, 5, Material.STONE);
        Block placed = block(5, 65, 5, Material.DIRT);
        placeAndBreak(5, 65, 5);
        BlockPlaceEvent place = new BlockPlaceEvent(placed, placed.getState(), against, new ItemStack(Material.DIRT),
                alex, true, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(place);

        assertTrue(place.isCancelled());
        List<String> reply = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 5 65 5 -----", reply.getFirst());
    }

    @Test
    public void breakingWhileInspectingIsCancelled() {
        BlockBreakEvent breaking = new BlockBreakEvent(block(5, 64, 5, Material.STONE), alex);
        server.getPluginManager().callEvent(breaking);
        assertTrue(breaking.isCancelled());
    }

    @Test
    public void anEmptyBlockHasNoHistory() {
        click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block(9, 64, 9, Material.STONE));
        assertEquals(List.of("No history for this block."), AwaitReplies.reply(server, alex));
    }

    @Test
    public void pageContinuesAnInspectorLookup() {
        Block block = block(5, 64, 5, Material.STONE);
        for (int i = 0; i < 5; i++) {
            placeAndBreak(5, 64, 5);
        }
        click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block);
        List<String> first = AwaitReplies.reply(server, alex);
        assertEquals(10, first.size(), first.toString());
        assertEquals("Page 1/2  [next >]", first.getLast());

        TestCommandManager commands = TestPlugins.commands();
        commands.run(alex, "hawkeye page 2");
        List<String> second = AwaitReplies.reply(server, alex);
        assertEquals("----- Hawkeye 5 64 5 -----", second.getFirst());
        assertEquals(4, second.size(), second.toString());
    }

    @Test
    public void aRepeatedClickRunsOneLookup() {
        Block block = block(5, 64, 5, Material.STONE);
        placeAndBreak(5, 64, 5);

        click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block);
        click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block);

        assertEquals(3, AwaitReplies.reply(server, alex).size());
        assertTrue(AwaitReplies.during(server, alex, 300).isEmpty());
    }

    @Test
    public void withoutTheInspectorClicksAreLeftAlone() {
        inspector.toggle(alex.getUniqueId());
        Block block = block(5, 64, 5, Material.STONE);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null, block);

        assertFalse(cancelled(click));
        assertTrue(AwaitReplies.during(server, alex, 200).isEmpty());
    }

    @Test
    public void losingThePermissionTurnsTheInspectorOff() {
        alex.setOp(false);

        PlayerInteractEvent click = click(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK, null,
                block(5, 64, 5, Material.STONE));

        assertFalse(cancelled(click));
        assertFalse(inspector.isInspecting(alex.getUniqueId()));
    }

    @Test
    public void leavingTurnsTheInspectorOff() {
        server.getPluginManager().callEvent(new PlayerQuitEvent(alex, Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(inspector.isInspecting(alex.getUniqueId()));
    }

    private Block block(int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(type);
        return block;
    }

    private void placeAndBreak(int x, int y, int z) {
        recorder.record(Action.BLOCK_PLACE, Actors.of(alex), world, x, y, z, "minecraft:stone", "minecraft:air", 0,
                null);
        recorder.record(Action.BLOCK_BREAK, Actors.FIRE, world, x, y, z, "minecraft:stone", "minecraft:air", 0, null);
    }

    private PlayerInteractEvent click(org.bukkit.event.block.Action action, @Nullable ItemStack item, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(alex, action, item, block, BlockFace.UP,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** A cancelled interaction denies both the clicked block and the item in hand. */
    private static boolean cancelled(PlayerInteractEvent event) {
        return event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY;
    }
}
