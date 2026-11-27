package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.core.service.dev.DevWorld;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shape of every fake event that is not a session change: what a player or the world does, with the
 * subject, secondary, amount and payload Hawkeye's own capture would record (docs/architecture.md,
 * "Tables"). Events come back unstamped (stamp 0); {@link FakeServer} sets their time.
 */
public final class FakeEvents {

    /** The block left behind by most breaks. */
    public static final String AIR = "minecraft:air";

    private static final Actor GROWTH = Actor.source("growth");
    private static final Actor WATER = Actor.source("water");
    private static final Actor LAVA = Actor.source("lava");
    private static final Actor PHYSICS = Actor.source("physics");
    private static final Actor PISTON = Actor.source("piston");
    private static final Actor FIRE = Actor.source("fire");
    private static final Actor DECAY = Actor.source("decay");
    private static final Actor NATURE = Actor.source("nature");
    private static final Actor HOPPER = Actor.source("hopper");
    private static final Actor CONSOLE = Actor.source("console");

    private final @NotNull SplittableRandom random;
    private final @NotNull List<DevWorld> worlds;
    private final int spread;

    /**
     * The shapes for one generator.
     *
     * @param random the generator's random source
     * @param worlds the worlds, the primary one first
     * @param spread how far from the primary centre homes lie
     */
    public FakeEvents(@NotNull SplittableRandom random, @NotNull List<DevWorld> worlds, int spread) {
        this.random = random;
        this.worlds = List.copyOf(worlds);
        this.spread = spread;
    }

    /**
     * A player breaking the block in front of them: a container comes with its contents (most of the
     * time), a sign with its text.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent playerBreak(@NotNull FakePlayer player) {
        walk(player);
        String subject = player.material(random, palette(player.world()));
        Payload payload = null;
        if (container(subject)) {
            payload = random.nextInt(10) < 6 ? new Payload.Inventory(FakeInventories.container(random)) : null;
        } else if (subject.contains("_sign")) {
            payload = new Payload.Sign(signText(), SignSides.EMPTY);
        }
        return at(player, player.actor(), Action.BLOCK_BREAK, subject, leftBehind(), 0, payload);
    }

    /**
     * A player breaking a filled chest (every payload kind occurs in a run of at least 35 events).
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent chestBreak(@NotNull FakePlayer player) {
        walk(player);
        return at(player, player.actor(), Action.BLOCK_BREAK, "minecraft:chest[facing=north,type=single,"
                + "waterlogged=false]", AIR, 0, new Payload.Inventory(FakeInventories.container(random)));
    }

    /**
     * One block of an explosion.
     *
     * @param world the world index
     * @param x     the centre x
     * @param y     the centre y
     * @param z     the centre z
     * @param actor who set it off
     * @return the event
     */
    public @NotNull PendingEvent burstBreak(int world, int x, int y, int z, @NotNull Actor actor) {
        return event(world, x + random.nextInt(-4, 5), y + random.nextInt(-3, 4), z + random.nextInt(-4, 5), actor,
                Action.BLOCK_BREAK, palette(world).pick(random), AIR, 0, null);
    }

    /**
     * The world breaking, placing or changing a block near a player: fire, decay, physics, pistons, nature.
     *
     * @param player the player nearby
     * @param action {@link Action#BLOCK_BREAK}, {@link Action#BLOCK_PLACE} or {@link Action#BLOCK_CHANGE}
     * @return the event
     */
    public @NotNull PendingEvent natural(@NotNull FakePlayer player, @NotNull Action action) {
        int x = player.x() + random.nextInt(-48, 49);
        int y = player.y() + random.nextInt(-8, 9);
        int z = player.z() + random.nextInt(-48, 49);
        return switch (action) {
            case BLOCK_BREAK -> {
                String subject = palette(player.world()).pick(random);
                Actor actor = subject.contains("leaves") ? DECAY : random.nextInt(4) == 0 ? FIRE
                        : random.nextBoolean() ? PHYSICS : PISTON;
                yield event(player.world(), x, y, z, actor, action, subject, AIR, 0, null);
            }
            case BLOCK_PLACE -> event(player.world(), x, y, z, PISTON, action, palette(player.world()).pick(random),
                    AIR, 0, null);
            default -> {
                FakeContent.Change change = FakeContent.change(random);
                yield event(player.world(), x, y, z, random.nextBoolean() ? PHYSICS : NATURE, Action.BLOCK_CHANGE,
                        change.after(), change.before(), 0, null);
            }
        };
    }

    /**
     * A player placing a block, mostly into air.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent place(@NotNull FakePlayer player) {
        walk(player);
        int roll = random.nextInt(20);
        String replaced = roll < 17 ? AIR : roll < 18 ? "minecraft:short_grass" : "minecraft:water[level=0]";
        return at(player, player.actor(), Action.BLOCK_PLACE, player.material(random, palette(player.world())),
                replaced, 0, null);
    }

    /**
     * Water or lava flowing from a bucket a player emptied, by the player or by the fluid itself.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent flow(@NotNull FakePlayer player) {
        walk(player);
        boolean lava = worlds.get(player.world()).dimension() == DevWorld.Dimension.NETHER || random.nextInt(5) == 0;
        Actor actor = random.nextInt(10) < 6 ? player.actor() : lava ? LAVA : WATER;
        String fluid = (lava ? "minecraft:lava[level=" : "minecraft:water[level=") + random.nextInt(8) + "]";
        return at(player, actor, Action.BLOCK_PLACE, fluid, AIR, 0, null);
    }

    /**
     * A player changing a block in place (stripping a log, tilling, making a path).
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent change(@NotNull FakePlayer player) {
        walk(player);
        FakeContent.Change change = FakeContent.change(random);
        return at(player, player.actor(), Action.BLOCK_CHANGE, change.after(), change.before(), 0, null);
    }

    /**
     * A player using a door, trapdoor, gate, lever or button.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent interact(@NotNull FakePlayer player) {
        walk(player);
        FakeContent.Change change = FakeContent.interaction(random);
        return at(player, player.actor(), Action.INTERACT, change.after(), change.before(), 0, null);
    }

    /**
     * A player writing on a sign, usually a blank one.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent sign(@NotNull FakePlayer player) {
        walk(player);
        String state = random.nextBoolean() ? "minecraft:oak_sign[rotation=" + 4 * random.nextInt(4)
                + ",waterlogged=false]" : "minecraft:oak_wall_sign[facing=north,waterlogged=false]";
        SignSides before = random.nextInt(4) == 0 ? signText() : SignSides.EMPTY;
        return at(player, player.actor(), Action.SIGN_EDIT, state, state, 0, new Payload.Sign(before, signText()));
    }

    /**
     * A crop on a player's farm ripening.
     *
     * @param owner the farm's owner
     * @return the event
     */
    public @NotNull PendingEvent grow(@NotNull FakePlayer owner) {
        return event(0, owner.homeX() + random.nextInt(24), owner.homeY(), owner.homeZ() + random.nextInt(24),
                GROWTH, Action.BLOCK_GROW, FakeContent.GROWN.pick(random), null, 0, null);
    }

    /**
     * Items put into or taken out of a container, mostly the few chests by the player's home, sometimes by a
     * hopper.
     *
     * @param player the player
     * @param action {@link Action#CONTAINER_DEPOSIT} or {@link Action#CONTAINER_WITHDRAW}
     * @return the event
     */
    public @NotNull PendingEvent container(@NotNull FakePlayer player, @NotNull Action action) {
        String item = FakeContent.ITEMS.pick(random);
        Optional<byte[]> bytes = FakeInventories.single(item, random);
        int amount = bytes.isPresent() ? 1 : random.nextInt(5) < 2 ? 64 : 1 + random.nextInt(64);
        Payload payload = bytes.<Payload>map(Payload.Item::new).orElse(null);
        Actor actor = random.nextInt(10) == 0 ? HOPPER : player.actor();
        if (random.nextInt(5) < 4) {
            return event(0, player.homeX() + random.nextInt(4), player.homeY(), player.homeZ() + 6 + random.nextInt(2),
                    actor, action, item, null, amount, payload);
        }
        return at(player, actor, action, item, null, amount, payload);
    }

    /**
     * Items put into or taken out of an inventory that is not a container in the world, where the player
     * stands.
     *
     * @param player the player
     * @param owner  a player whose ender chest or inventory it may be
     * @param action {@link Action#VIRTUAL_DEPOSIT} or {@link Action#VIRTUAL_WITHDRAW}
     * @return the event
     */
    public @NotNull PendingEvent virtual(@NotNull FakePlayer player, @NotNull String owner, @NotNull Action action) {
        String item = FakeContent.ITEMS.pick(random);
        Optional<byte[]> bytes = FakeInventories.single(item, random);
        int amount = bytes.isPresent() ? 1 : 1 + random.nextInt(64);
        Payload payload = new Payload.VirtualItem(FakeContent.virtualContainer(random, owner),
                bytes.orElse(new byte[0]));
        return at(player, player.actor(), action, item, null, amount, payload);
    }

    /**
     * An item dropped, picked up, crafted, thrown or traded where the player stands.
     *
     * @param player    the player
     * @param action    the item action
     * @param palette   the items it may be
     * @param maxAmount the largest count, 1 for a single item
     * @return the event
     */
    public @NotNull PendingEvent item(@NotNull FakePlayer player, @NotNull Action action, @NotNull FakePalette palette,
            int maxAmount) {
        String item = palette.pick(random);
        Optional<byte[]> bytes = FakeInventories.single(item, random);
        int amount = bytes.isPresent() || maxAmount == 1 ? 1 : 1 + random.nextInt(maxAmount);
        return at(player, player.actor(), action, item, null, amount, bytes.<Payload>map(Payload.Item::new)
                .orElse(null));
    }

    /**
     * A tool or armour piece wearing out.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent itemBreak(@NotNull FakePlayer player) {
        String item = FakeContent.WORN_OUT.pick(random);
        return at(player, player.actor(), Action.ITEM_BREAK, item, null, 1,
                new Payload.Item(FakeInventories.wornOut(item, random)));
    }

    /**
     * A kill near a player, mostly by them, otherwise by the world.
     *
     * @param player  the player nearby
     * @param mobOnly {@code true} to kill a mob, never a player or an armour stand
     * @return the event
     */
    public @NotNull PendingEvent kill(@NotNull FakePlayer player, boolean mobOnly) {
        String victim = mobOnly ? "minecraft:zombie" : FakeContent.KILLED.pick(random);
        Actor killer = random.nextInt(5) < 4 ? player.actor() : killCause();
        Payload payload = victim.equals("minecraft:player") ? null
                : new Payload.Entity(FakeEntities.mob(victim, random));
        return event(player.world(), player.x() + random.nextInt(-6, 7), player.y(), player.z() + random.nextInt(-6, 7),
                killer, Action.ENTITY_KILL, victim, null, 0, payload);
    }

    /**
     * A player killed by another player or the world (the second half of a death).
     *
     * @param world  the world index of the death
     * @param x      where they died, x
     * @param y      where they died, y
     * @param z      where they died, z
     * @param killer the other player, or empty for the world
     * @return the event
     */
    public @NotNull PendingEvent playerKill(int world, int x, int y, int z, @NotNull Optional<Actor> killer) {
        return event(world, x, y, z, killer.orElseGet(this::killCause), Action.ENTITY_KILL, "minecraft:player", null,
                0, null);
    }

    /**
     * An entity placed or spawned by a player.
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent spawn(@NotNull FakePlayer player) {
        return near(player, Action.ENTITY_SPAWN, FakeContent.PLACED_ENTITIES.pick(random),
                new Payload.EntityRef(new UUID(random.nextLong(), random.nextLong())));
    }

    /**
     * An object a player destroyed or took (or a mob they caught in a bucket).
     *
     * @param player the player
     * @return the event
     */
    public @NotNull PendingEvent remove(@NotNull FakePlayer player) {
        String type = FakeContent.REMOVED_ENTITIES.pick(random);
        DevWorld bounds = worlds.get(player.world());
        int x = player.x() + random.nextInt(-3, 4);
        int y = Math.clamp(player.y(), bounds.minY(), bounds.maxY());
        int z = player.z() + random.nextInt(-3, 4);
        return event(player.world(), x, y, z, player.actor(), Action.ENTITY_REMOVE, type, null, 0,
                new Payload.Entity(FakeEntities.object(type, x, y, z, random)));
    }

    /**
     * A player handling an animal: shearing, leashing, unleashing, taming, naming or dyeing it.
     *
     * @param player the player
     * @param action the entity action
     * @return the event
     */
    public @NotNull PendingEvent handle(@NotNull FakePlayer player, @NotNull Action action) {
        return switch (action) {
            case ENTITY_SHEAR -> near(player, action, random.nextInt(10) < 9 ? "minecraft:sheep" : "minecraft:mooshroom",
                    null);
            case ENTITY_TAME -> near(player, action, random.nextBoolean() ? "minecraft:wolf" : "minecraft:cat", null);
            case ENTITY_NAME -> near(player, action, FakeContent.HANDLED_ENTITIES.pick(random),
                    new Payload.Text(FakeContent.petName(random)));
            case ENTITY_DYE -> near(player, action, random.nextBoolean() ? "minecraft:sheep" : "minecraft:wolf",
                    new Payload.Text(FakeContent.colour(random)));
            default -> near(player, action, FakeContent.HANDLED_ENTITIES.pick(random), null);
        };
    }

    /**
     * Something a player said or did that is about them, not a place: chat, a command (now and then by the
     * console instead, at the primary centre), a book, a game mode change.
     *
     * @param player  the player
     * @param action  {@link Action#CHAT}, {@link Action#COMMAND}, {@link Action#BOOK_EDIT} or
     *                {@link Action#GAMEMODE_CHANGE}
     * @param someone another player's name, for commands that name one
     * @return the event
     */
    public @NotNull PendingEvent message(@NotNull FakePlayer player, @NotNull Action action, @NotNull String someone) {
        if (action == Action.COMMAND && random.nextInt(50) == 0) {
            DevWorld primary = worlds.getFirst();
            return event(0, primary.x(), primary.y(), primary.z(), CONSOLE, action, "", null, 0,
                    new Payload.Text(FakeContent.command(random, someone, true)));
        }
        String text = switch (action) {
            case CHAT -> FakeContent.chat(random);
            case COMMAND -> FakeContent.command(random, someone, false);
            case BOOK_EDIT -> FakeContent.book(random);
            default -> FakeContent.gameMode(random);
        };
        return at(player, player.actor(), action, "", null, 0, new Payload.Text(text));
    }

    /**
     * A join or a quit where the player stands; a quit is sometimes a kick.
     *
     * @param player the player
     * @param action {@link Action#JOIN} or {@link Action#QUIT}
     * @return the event
     */
    public @NotNull PendingEvent session(@NotNull FakePlayer player, @NotNull Action action) {
        Payload payload = action == Action.QUIT && random.nextInt(100) < 3
                ? new Payload.Text(FakeContent.kick(random)) : null;
        return at(player, player.actor(), action, "", null, 0, payload);
    }

    /**
     * A copy of a player's inventory where they stand.
     *
     * @param player the player
     * @param action the snapshot action
     * @param detail what the moment was, or empty
     * @return the event; its amount is the number of items
     */
    public @NotNull PendingEvent snapshot(@NotNull FakePlayer player, @NotNull Action action, @NotNull String detail) {
        List<Payload.Slot> slots = player.inventory(random);
        return at(player, player.actor(), action, "", null, FakeInventories.count(slots),
                new Payload.Snapshot(detail, slots));
    }

    /**
     * Moves a player a step, staying around their home in the primary world and around the centre elsewhere.
     *
     * @param player the player
     */
    public void walk(@NotNull FakePlayer player) {
        DevWorld world = worlds.get(player.world());
        if (player.world() == 0) {
            player.step(random, world, player.homeX(), player.homeZ(), spread);
        } else {
            player.step(random, world, world.x(), world.z(), spread / 2);
        }
    }

    /**
     * The block palette of a world.
     *
     * @param world the world index
     * @return its blocks
     */
    public @NotNull FakePalette palette(int world) {
        DevWorld.Dimension dimension = worlds.get(world).dimension();
        return FakeContent.blocks(dimension == DevWorld.Dimension.NETHER, dimension == DevWorld.Dimension.END);
    }

    /**
     * An event at a place, clamped into the world's heights and the packed position's range.
     *
     * @param world     the world index
     * @param x         the block x
     * @param y         the block y
     * @param z         the block z
     * @param actor     who or what caused it
     * @param action    what happened
     * @param subject   the subject value, or empty
     * @param secondary the other side of a block change, or {@code null}
     * @param amount    the item count, or 0
     * @param payload   the payload, or {@code null}
     * @return the event, unstamped
     */
    public @NotNull PendingEvent event(int world, int x, int y, int z, @NotNull Actor actor, @NotNull Action action,
            @NotNull String subject, @Nullable String secondary, int amount, @Nullable Payload payload) {
        DevWorld bounds = worlds.get(world);
        long position = BlockPositions.pack(Math.clamp(x, BlockPositions.MIN_HORIZONTAL, BlockPositions.MAX_HORIZONTAL),
                Math.clamp(y, bounds.minY(), bounds.maxY()),
                Math.clamp(z, BlockPositions.MIN_HORIZONTAL, BlockPositions.MAX_HORIZONTAL));
        return new PendingEvent(0, bounds.name(), position, actor, action, subject, secondary, amount,
                payload == null ? null : PayloadCodec.encode(payload));
    }

    private @NotNull PendingEvent at(@NotNull FakePlayer player, @NotNull Actor actor, @NotNull Action action,
            @NotNull String subject, @Nullable String secondary, int amount, @Nullable Payload payload) {
        return event(player.world(), player.x(), player.y(), player.z(), actor, action, subject, secondary, amount,
                payload);
    }

    private @NotNull PendingEvent near(@NotNull FakePlayer player, @NotNull Action action, @NotNull String type,
            @Nullable Payload payload) {
        return event(player.world(), player.x() + random.nextInt(-3, 4), player.y(), player.z() + random.nextInt(-3, 4),
                player.actor(), action, type, null, 0, payload);
    }

    private @NotNull Actor killCause() {
        return Actor.source(FakeContent.KILL_CAUSES.get(random.nextInt(FakeContent.KILL_CAUSES.size())));
    }

    private @NotNull String leftBehind() {
        return random.nextInt(50) == 0 ? "minecraft:water[level=0]" : AIR;
    }

    private @NotNull SignSides signText() {
        List<String> lines = new ArrayList<>(4);
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            lines.add(FakeItems.text(FakeContent.words(random, 1 + random.nextInt(3))));
        }
        return new SignSides(lines, List.of());
    }

    private static boolean container(@NotNull String state) {
        return state.startsWith("minecraft:chest") || state.startsWith("minecraft:barrel")
                || state.startsWith("minecraft:hopper");
    }
}
