package gg.moonrise.hawkeye.core.service.dev.fake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.dev.DevWorld;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.actor.ActorKind;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.serialize.ItemDetails;
import gg.moonrise.hawkeye.util.serialize.ItemSummary;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class FakeServerTest {

    private static final long START = EventIds.EPOCH_MILLIS + 86_400_000L * 300;
    private static final long END = START + 86_400_000L * 30;
    private static final List<DevWorld> WORLDS = List.of(
            new DevWorld("lobby", DevWorld.Dimension.OVERWORLD, 1_000, 70, -2_000, -64, 319),
            new DevWorld("lobby_nether", DevWorld.Dimension.NETHER, 0, 64, 0, 0, 127),
            new DevWorld("lobby_the_end", DevWorld.Dimension.END, 0, 64, 0, 0, 255));

    @Test
    public void theSameArgumentsGiveTheSameStream() {
        List<PendingEvent> first = stream(7, 3_000);
        List<PendingEvent> second = stream(7, 3_000);
        for (int i = 0; i < first.size(); i++) {
            assertSame(first.get(i), second.get(i), i);
        }
        List<PendingEvent> other = stream(8, 3_000);
        int differing = 0;
        for (int i = 0; i < first.size(); i++) {
            if (!describe(first.get(i)).equals(describe(other.get(i)))) {
                differing++;
            }
        }
        assertTrue(differing > first.size() / 2, "another seed gives another stream: " + differing);
    }

    @Test
    public void oneEventPerActionGivesEveryActionAndPayloadOnce() {
        List<PendingEvent> events = stream(3, Action.values().length);
        Map<Action, Long> counts = events.stream().collect(Collectors.groupingBy(PendingEvent::action,
                () -> new EnumMap<>(Action.class), Collectors.counting()));
        for (Action action : Action.values()) {
            assertEquals(1L, counts.get(action), action.name());
        }
        Set<Class<?>> kinds = new HashSet<>();
        events.stream().filter(event -> event.data() != null)
                .forEach(event -> kinds.add(PayloadCodec.decode(event.data()).getClass()));
        assertEquals(Set.of(Payload.class.getPermittedSubclasses()), kinds);
    }

    @Test
    public void longerRunsHaveEveryActionToo() {
        for (int count : List.of(500, 5_000)) {
            Set<Action> seen = EnumSet.noneOf(Action.class);
            stream(count, count).forEach(event -> seen.add(event.action()));
            assertEquals(EnumSet.allOf(Action.class), seen, "count " + count);
        }
    }

    @Test
    public void timesNeverDecreaseAndStayInTheWindow() {
        long previous = Long.MIN_VALUE;
        for (PendingEvent event : stream(11, 20_000)) {
            long millis = event.epochMillis();
            assertTrue(millis >= previous, "time went back at " + event);
            assertTrue(millis >= START && millis <= END, Long.toString(millis));
            previous = millis;
        }
    }

    @Test
    public void placesAreInsideTheWorlds() {
        Map<String, DevWorld> byName = WORLDS.stream().collect(Collectors.toMap(DevWorld::name, world -> world));
        Set<String> seen = new HashSet<>();
        for (PendingEvent event : stream(5, 20_000)) {
            DevWorld world = byName.get(event.world());
            assertNotNull(world, event.world());
            seen.add(world.name());
            int x = BlockPositions.x(event.position());
            int y = BlockPositions.y(event.position());
            int z = BlockPositions.z(event.position());
            assertTrue(y >= world.minY() && y <= world.maxY(), event.toString());
            assertEquals(event.position(), BlockPositions.pack(x, y, z));
        }
        assertEquals(byName.keySet(), seen, "every world is visited");
    }

    @Test
    public void everyActionHasItsShape() {
        for (PendingEvent event : stream(13, 20_000)) {
            checkShape(event);
        }
    }

    @Test
    public void someItemsAreNamedEnchantedOrBooks() {
        boolean named = false;
        boolean enchanted = false;
        boolean book = false;
        for (PendingEvent event : stream(17, 20_000)) {
            for (byte[] item : items(event)) {
                ItemDetails details = ItemDetails.of(item).orElseThrow();
                named |= details.name().isPresent();
                enchanted |= !details.enchantments().isEmpty();
                book |= details.book().isPresent();
            }
        }
        assertTrue(named && enchanted && book, named + " " + enchanted + " " + book);
    }

    @Test
    public void playersActOnlyWhileOnline() {
        Map<String, Action> lastSession = new HashMap<>();
        Set<String> joined = new HashSet<>();
        for (PendingEvent event : stream(19, 20_000)) {
            Actor actor = event.actor();
            if (actor.kind() != ActorKind.PLAYER) {
                continue;
            }
            Action action = event.action();
            if (action == Action.JOIN || action == Action.QUIT) {
                assertNotEquals(action, lastSession.get(actor.key()), "two " + action + " in a row for " + actor);
                lastSession.put(actor.key(), action);
                joined.add(actor.key());
            } else if (action.family() != ActionFamily.SNAPSHOT) {
                assertNotEquals(Action.QUIT, lastSession.get(actor.key()), actor + " acted while offline: " + event);
            }
        }
        assertTrue(joined.size() > 5, "players come and go: " + joined.size());
    }

    @Test
    public void playersAreStableAndUnique() {
        Set<String> names = new HashSet<>();
        Set<UUID> uuids = new HashSet<>();
        for (int i = 0; i < FakeServer.MAX_PLAYERS; i++) {
            Actor player = FakeServer.player(i);
            assertTrue(player.name().length() <= 16 && player.name().matches("[A-Za-z0-9]+"), player.name());
            UUID uuid = player.uuid().orElseThrow();
            assertEquals(3, uuid.version());
            assertTrue(names.add(player.name()), player.name());
            assertTrue(uuids.add(uuid));
            assertEquals(player, FakeServer.player(i));
        }
        assertEquals("SteveCraft", FakeServer.player(0).name());
    }

    @Test
    public void thePlayerCountFollowsTheEvents() {
        assertEquals(8, FakeServer.playersFor(1));
        assertEquals(500, FakeServer.playersFor(1_000_000));
        assertEquals(8_000, FakeServer.playersFor(1_000_000_000));
        assertEquals(8, new FakeServer(1, 10, START, END, WORLDS).players());
    }

    @Test
    public void aCallPastTheCountThrows() {
        FakeServer server = new FakeServer(1, 3, START, END, List.of());
        server.next();
        server.next();
        assertEquals("world", server.next().world(), "no worlds means the vanilla ones");
        assertThrows(IllegalStateException.class, server::next);
    }

    private static void checkShape(@NotNull PendingEvent event) {
        Action action = event.action();
        Payload payload = event.data() == null ? null : PayloadCodec.decode(event.data());
        switch (action.family()) {
            case BLOCK -> {
                assertTrue(event.subject().startsWith("minecraft:"), event.toString());
                switch (action) {
                    case BLOCK_GROW -> {
                        assertNull(event.secondary());
                        assertEquals("#growth", event.actor().key());
                    }
                    case SIGN_EDIT -> {
                        assertEquals(event.subject(), event.secondary());
                        Payload.Sign sign = (Payload.Sign) payload;
                        assertNotNull(sign);
                        assertFalse(sign.after().front().isEmpty());
                        sign.after().front().forEach(line -> GsonComponentSerializer.gson().deserialize(line));
                        sign.before().front().forEach(line -> GsonComponentSerializer.gson().deserialize(line));
                    }
                    default -> assertNotNull(event.secondary(), event.toString());
                }
                if (action == Action.BLOCK_BREAK && payload != null) {
                    assertTrue(payload instanceof Payload.Inventory || payload instanceof Payload.Sign, event.toString());
                    if (payload instanceof Payload.Sign sign) {
                        sign.before().front().forEach(line -> GsonComponentSerializer.gson().deserialize(line));
                    }
                } else if (action != Action.SIGN_EDIT) {
                    assertNull(payload, event.toString());
                }
            }
            case CONTAINER, ITEM -> {
                assertTrue(event.subject().startsWith("minecraft:"), event.toString());
                assertNull(event.secondary());
                assertTrue(event.amount() >= 1 && event.amount() <= 64, event.toString());
                switch (action) {
                    case VIRTUAL_DEPOSIT, VIRTUAL_WITHDRAW -> {
                        Payload.VirtualItem virtual = (Payload.VirtualItem) payload;
                        assertNotNull(virtual);
                        assertFalse(virtual.container().isEmpty());
                        if (virtual.item().length > 0) {
                            assertSingle(event, virtual.item());
                        }
                    }
                    case ITEM_BREAK -> assertSingle(event, ((Payload.Item) payload).bytes());
                    default -> {
                        if (payload != null) {
                            assertSingle(event, ((Payload.Item) payload).bytes());
                        }
                    }
                }
            }
            case ENTITY -> {
                assertTrue(event.subject().startsWith("minecraft:"), event.toString());
                assertNull(event.secondary());
                assertEquals(0, event.amount());
                switch (action) {
                    case ENTITY_KILL -> assertEquals(event.subject().equals("minecraft:player"), payload == null);
                    case ENTITY_SPAWN -> assertTrue(payload instanceof Payload.EntityRef);
                    case ENTITY_REMOVE -> assertHangsAtTheEvent(event, (Payload.Entity) payload);
                    case ENTITY_NAME, ENTITY_DYE -> assertTrue(payload instanceof Payload.Text);
                    default -> assertNull(payload);
                }
            }
            case MESSAGE -> {
                assertEquals("", event.subject());
                Payload.Text text = (Payload.Text) payload;
                assertNotNull(text);
                if (action == Action.COMMAND) {
                    assertTrue(text.text().startsWith("/"), text.text());
                }
                if (action == Action.BOOK_EDIT) {
                    assertTrue(text.text().startsWith("title: "), text.text());
                }
            }
            case SESSION -> {
                assertEquals("", event.subject());
                assertTrue(payload == null || action == Action.QUIT
                        && ((Payload.Text) payload).text().startsWith("kicked: "), event.toString());
            }
            case SNAPSHOT -> {
                Payload.Snapshot snapshot = (Payload.Snapshot) payload;
                assertNotNull(snapshot);
                int total = 0;
                for (Payload.Slot slot : snapshot.slots()) {
                    assertTrue(slot.index() >= 0 && slot.index() <= Payload.Snapshot.CURSOR, slot.toString());
                    total += ItemSummary.of(slot.item()).orElseThrow().count();
                }
                assertEquals(total, event.amount());
                if (action == Action.SNAPSHOT_JOIN || action == Action.SNAPSHOT_QUIT) {
                    assertEquals("", snapshot.detail());
                } else {
                    assertFalse(snapshot.detail().isEmpty());
                }
            }
        }
        for (byte[] item : items(event)) {
            assertTrue(ItemSummary.of(item).isPresent(), event.toString());
        }
    }

    private static void assertSingle(@NotNull PendingEvent event, byte @NotNull [] item) {
        ItemSummary summary = ItemSummary.of(item).orElseThrow();
        assertEquals(event.subject(), summary.id(), event.toString());
        assertEquals(1, summary.count(), event.toString());
    }

    private static @NotNull List<byte[]> items(@NotNull PendingEvent event) {
        if (event.data() == null) {
            return List.of();
        }
        return switch (PayloadCodec.decode(event.data())) {
            case Payload.Item item -> List.of(item.bytes());
            case Payload.VirtualItem virtual -> virtual.item().length == 0 ? List.of() : List.of(virtual.item());
            case Payload.Inventory inventory -> inventory.slots().stream().map(Payload.Slot::item).toList();
            case Payload.Snapshot snapshot -> snapshot.slots().stream().map(Payload.Slot::item).toList();
            default -> List.of();
        };
    }

    private static void assertSame(@NotNull PendingEvent expected, @NotNull PendingEvent actual, int index) {
        assertEquals(describe(expected), describe(actual), "event " + index);
        assertArrayEquals(expected.data(), actual.data(), "event " + index);
    }

    private static @NotNull String describe(@NotNull PendingEvent event) {
        return event.stamp() + " " + event.world() + " " + event.position() + " " + event.actor() + " "
                + event.action() + " " + event.subject() + " " + event.secondary() + " " + event.amount();
    }

    private static @NotNull List<PendingEvent> stream(long seed, int count) {
        FakeServer server = new FakeServer(seed, count, START, END, WORLDS);
        List<PendingEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(server.next());
        }
        return events;
    }

    /** A removed painting or item frame names the event's own block; other objects name no place. */
    private static void assertHangsAtTheEvent(@NotNull PendingEvent event, @Nullable Payload.Entity entity) {
        assertNotNull(entity, event.toString());
        NbtTag.NbtCompound nbt = NbtRoot.read(entity.bytes()).compound();
        assertEquals(new NbtTag.NbtString(event.subject()), nbt.get("id").orElseThrow());
        boolean hangs = Set.of("minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting")
                .contains(event.subject());
        assertEquals(hangs, nbt.get("TileX").isPresent(), event.subject());
        if (hangs) {
            assertEquals(new NbtTag.NbtInt(BlockPositions.x(event.position())), nbt.get("TileX").orElseThrow());
            assertEquals(new NbtTag.NbtInt(BlockPositions.y(event.position())), nbt.get("TileY").orElseThrow());
            assertEquals(new NbtTag.NbtInt(BlockPositions.z(event.position())), nbt.get("TileZ").orElseThrow());
        }
    }
}
