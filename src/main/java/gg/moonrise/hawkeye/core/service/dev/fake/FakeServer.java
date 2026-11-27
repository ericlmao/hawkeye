package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.core.service.dev.DevWorld;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A busy survival server as a seeded stream of fake events (dev mode): fake players join and quit, mine and
 * build around their homes, store items, fight, farm, trade, chat, teleport and die, and the world grows,
 * flows and explodes around them. A run of {@code count} events spreads them over a time window in
 * non-decreasing order, and once it holds at least one event per {@link Action} every action and every
 * payload kind occurs. The same arguments give the same stream. Pure Java: no server, clock or I/O.
 *
 * <p>A player causes events only between their JOIN and their QUIT (their inventory snapshots aside), and
 * between {@link #minOnline()} and {@link #maxOnline()} players are online at a time. Event shapes are
 * {@link FakeEvents}'.
 */
public final class FakeServer {

    /** The fewest fake players a run has. */
    public static final int MIN_PLAYERS = 8;

    /** The most fake players a run has. */
    public static final int MAX_PLAYERS = 8_000;

    /** Events per fake player, until {@link #MAX_PLAYERS}. */
    public static final int EVENTS_PER_PLAYER = 2_000;

    private static final String[] FIRST = {"Steve", "Alex", "Ender", "Nova", "Frost", "Blaze", "Pixel", "Shadow",
        "Sky", "Iron", "Gold", "Dragon", "Ruby", "Echo", "Luna", "Comet"};
    private static final String[] SECOND = {"Craft", "Miner", "Block", "Wolf", "Fox", "Bear", "Hawk", "Stone",
        "Smith", "Rider", "Lord", "King", "Bot", "Star", "Byte", "Ace"};
    private static final Actor[] BURST_SOURCES = {Actor.source("creeper"), Actor.source("tnt"),
        Actor.source("explosion")};
    private static final Action[] ACTIONS = Action.values();

    private final SplittableRandom random;
    private final long count;
    private final long startMillis;
    private final long endMillis;
    private final long stride;
    private final List<DevWorld> worlds;
    private final FakePlayer[] players;
    private final List<FakePlayer> online = new ArrayList<>();
    private final List<FakePlayer> offline = new ArrayList<>();
    private final Deque<Supplier<PendingEvent>> followUps = new ArrayDeque<>();
    private final FakeEvents events;
    private final int minOnline;
    private final int maxOnline;
    private final int spread;
    private long index;
    private long millis;
    private int sequence;
    private int nextForced;
    private long forcedAt;
    private int burstLeft;
    private @Nullable FakePlayer burstPlayer;
    private Actor burstSource = BURST_SOURCES[0];
    private int burstWorld;
    private int burstX;
    private int burstY;
    private int burstZ;

    /**
     * A server that produces {@code count} events between two times.
     *
     * @param seed        the random seed (the same seed and arguments give the same events)
     * @param count       how many events {@link #next()} gives, at least 1
     * @param startMillis the earliest event time, Unix milliseconds
     * @param endMillis   the latest event time, after {@code startMillis}
     * @param worlds      the worlds, the primary one first; empty for {@link DevWorld#DEFAULTS}
     */
    public FakeServer(long seed, long count, long startMillis, long endMillis, @NotNull List<DevWorld> worlds) {
        if (count < 1 || endMillis <= startMillis) {
            throw new IllegalArgumentException("a fake server needs at least one event and a time window");
        }
        this.random = new SplittableRandom(seed);
        this.count = count;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.stride = Math.max(1, (endMillis - startMillis) / count);
        this.millis = startMillis;
        this.worlds = worlds.isEmpty() ? DevWorld.DEFAULTS : List.copyOf(worlds);
        this.players = new FakePlayer[playersFor(count)];
        this.maxOnline = Math.clamp(players.length / 4, 3, 120);
        this.minOnline = Math.max(2, maxOnline / 4);
        this.spread = Math.min(4_096, 128 + 8 * players.length);
        this.events = new FakeEvents(random, this.worlds, spread);
        DevWorld primary = this.worlds.getFirst();
        for (int i = 0; i < players.length; i++) {
            int x = i == 0 ? primary.x() : primary.x() + random.nextInt(-spread, spread + 1);
            int z = i == 0 ? primary.z() : primary.z() + random.nextInt(-spread, spread + 1);
            int y = Math.clamp(primary.y() + (i == 0 ? 0 : random.nextInt(-4, 9)), primary.minY(), primary.maxY() - 1);
            players[i] = new FakePlayer(i, player(i), x, y, z);
            offline.add(players[i]);
        }
        // Players already online when the window starts have no JOIN event.
        int startOnline = (minOnline + maxOnline + 1) / 2;
        while (online.size() < startOnline) {
            setOnline(takeOffline());
        }
        this.forcedAt = count >= ACTIONS.length ? 0 : -1;
    }

    /**
     * How many fake players a run of some events has.
     *
     * @param count the events
     * @return {@code count / EVENTS_PER_PLAYER}, kept between {@link #MIN_PLAYERS} and {@link #MAX_PLAYERS}
     */
    public static int playersFor(long count) {
        return Math.clamp(count / EVENTS_PER_PLAYER, MIN_PLAYERS, MAX_PLAYERS);
    }

    /**
     * Fake player number {@code index}: the same name and UUID in every run and every database. Names are
     * two words and a number ({@code SteveCraft}, ..., {@code LunaAce31}), at most 16 letters and digits; the
     * UUID is a version 3 UUID of the name.
     *
     * @param index 0 to {@link #MAX_PLAYERS} - 1
     * @return the player
     */
    public static @NotNull Actor player(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("a player index is not negative: " + index);
        }
        int round = index / (FIRST.length * SECOND.length);
        String name = FIRST[index % FIRST.length] + SECOND[(index / FIRST.length) % SECOND.length]
                + (round == 0 ? "" : Integer.toString(round));
        return Actor.player(UUID.nameUUIDFromBytes(("HawkeyeDev:" + name).getBytes(StandardCharsets.UTF_8)), name);
    }

    /**
     * How many fake players this server has.
     *
     * @return the player count
     */
    public int players() {
        return players.length;
    }

    /**
     * The fewest players online at a time (one fewer right after a forced quit).
     *
     * @return the count
     */
    public int minOnline() {
        return minOnline;
    }

    /**
     * The most players online at a time (a little more right after a forced join).
     *
     * @return the count
     */
    public int maxOnline() {
        return maxOnline;
    }

    /**
     * The next event.
     *
     * @return the event, stamped no earlier than the previous one and inside the window
     * @throws IllegalStateException after {@code count} events
     */
    public @NotNull PendingEvent next() {
        if (index >= count) {
            throw new IllegalStateException("this fake server gives " + count + " events");
        }
        long at = index++;
        PendingEvent event;
        if (at == forcedAt) {
            Action action = ACTIONS[nextForced++];
            forcedAt = nextForced < ACTIONS.length ? nextForced * count / ACTIONS.length : -1;
            timeOf(at);
            event = forced(action);
        } else if (!followUps.isEmpty()) {
            soonAfter();
            event = followUps.poll().get();
        } else if (burstLeft > 0) {
            soonAfter();
            event = burst();
        } else {
            timeOf(at);
            event = activity(Kind.pick(random));
        }
        sequence = (sequence + 1) & EventIds.MAX_SEQUENCE;
        return event.withStamp(EventIds.stamp(millis, sequence));
    }

    /** An ordinary event: its share of the window, never before the previous one. */
    private void timeOf(long at) {
        long base = startMillis + (long) ((endMillis - startMillis) * (at / (double) count));
        millis = Math.min(endMillis, Math.max(millis, base + random.nextLong(stride)));
    }

    /** A follow-up or the next block of an explosion: a few milliseconds after the previous event. */
    private void soonAfter() {
        millis = Math.min(endMillis, millis + random.nextInt(3));
    }

    /** The event that makes sure an action occurs: the action itself, its partners queued behind it. */
    private @NotNull PendingEvent forced(@NotNull Action action) {
        FakePlayer player = active();
        return switch (action) {
            case BLOCK_BREAK -> events.chestBreak(player);
            case BLOCK_PLACE -> events.place(player);
            case BLOCK_CHANGE -> events.change(player);
            case SIGN_EDIT -> events.sign(player);
            case INTERACT -> events.interact(player);
            case BLOCK_GROW -> events.grow(player);
            case CONTAINER_DEPOSIT, CONTAINER_WITHDRAW -> events.container(player, action);
            case VIRTUAL_DEPOSIT, VIRTUAL_WITHDRAW -> events.virtual(player, someone(), action);
            case ITEM_DROP, ITEM_PICKUP, ITEM_CRAFT -> events.item(player, action, FakeContent.ITEMS, 64);
            case ITEM_THROW -> events.item(player, action, FakeContent.THROWN, 1);
            case ITEM_BREAK -> events.itemBreak(player);
            case ITEM_TRADE -> events.item(player, action, FakeContent.TRADED, 12);
            case ENTITY_KILL -> events.kill(player, true);
            case ENTITY_SPAWN -> events.spawn(player);
            case ENTITY_REMOVE -> events.remove(player);
            case ENTITY_SHEAR, ENTITY_NAME, ENTITY_LEASH, ENTITY_UNLEASH, ENTITY_TAME, ENTITY_DYE ->
                    events.handle(player, action);
            case CHAT, COMMAND, BOOK_EDIT, GAMEMODE_CHANGE -> events.message(player, action, someone());
            case JOIN -> join(false);
            case SNAPSHOT_JOIN -> join(true);
            case QUIT -> quit(true);
            case SNAPSHOT_QUIT -> quit(false);
            case SNAPSHOT_TELEPORT -> teleport();
            case SNAPSHOT_DEATH -> death();
        };
    }

    /** What the server does next when nothing is forced, queued or exploding. */
    private @NotNull PendingEvent activity(@NotNull Kind kind) {
        if (online.size() < minOnline) {
            return join(false);
        }
        if (online.size() > maxOnline) {
            return quit(false);
        }
        return switch (kind) {
            case BREAK -> events.playerBreak(active());
            case EXPLOSION -> explosion();
            case NATURAL_BREAK -> events.natural(active(), Action.BLOCK_BREAK);
            case PLACE -> events.place(active());
            case FLOW -> events.flow(active());
            case PISTON_PLACE -> events.natural(active(), Action.BLOCK_PLACE);
            case CHANGE -> events.change(active());
            case NATURAL_CHANGE -> events.natural(active(), Action.BLOCK_CHANGE);
            case SIGN -> events.sign(active());
            case INTERACT -> events.interact(active());
            case GROW -> events.grow(active());
            case DEPOSIT -> events.container(active(), Action.CONTAINER_DEPOSIT);
            case WITHDRAW -> events.container(active(), Action.CONTAINER_WITHDRAW);
            case VIRTUAL_DEPOSIT -> events.virtual(active(), someone(), Action.VIRTUAL_DEPOSIT);
            case VIRTUAL_WITHDRAW -> events.virtual(active(), someone(), Action.VIRTUAL_WITHDRAW);
            case DROP -> events.item(active(), Action.ITEM_DROP, FakeContent.ITEMS, 64);
            case PICKUP -> events.item(active(), Action.ITEM_PICKUP, FakeContent.ITEMS, 64);
            case CRAFT -> events.item(active(), Action.ITEM_CRAFT, FakeContent.ITEMS, 64);
            case THROW -> events.item(active(), Action.ITEM_THROW, FakeContent.THROWN, 1);
            case ITEM_BREAK -> events.itemBreak(active());
            case TRADE -> events.item(active(), Action.ITEM_TRADE, FakeContent.TRADED, 12);
            case KILL -> events.kill(active(), false);
            case SPAWN -> events.spawn(active());
            case REMOVE -> events.remove(active());
            case SHEAR -> events.handle(active(), Action.ENTITY_SHEAR);
            case NAME -> events.handle(active(), Action.ENTITY_NAME);
            case LEASH -> events.handle(active(), Action.ENTITY_LEASH);
            case UNLEASH -> events.handle(active(), Action.ENTITY_UNLEASH);
            case TAME -> events.handle(active(), Action.ENTITY_TAME);
            case DYE -> events.handle(active(), Action.ENTITY_DYE);
            case CHAT -> events.message(active(), Action.CHAT, someone());
            case COMMAND -> events.message(active(), Action.COMMAND, someone());
            case BOOK -> events.message(active(), Action.BOOK_EDIT, someone());
            case GAMEMODE -> events.message(active(), Action.GAMEMODE_CHANGE, someone());
            case JOIN -> online.size() < maxOnline ? join(false) : quit(false);
            case QUIT -> online.size() > minOnline ? quit(false) : join(false);
            case TELEPORT -> teleport();
            case DEATH -> death();
        };
    }

    /**
     * A player joins: the JOIN, then their inventory; or, with {@code snapshotFirst}, the inventory and then
     * the JOIN (they count as online from the JOIN on).
     */
    private @NotNull PendingEvent join(boolean snapshotFirst) {
        if (offline.isEmpty()) {
            return quit(false);
        }
        FakePlayer player = takeOffline();
        player.goHome();
        if (snapshotFirst) {
            followUps.add(() -> {
                setOnline(player);
                return events.session(player, Action.JOIN);
            });
            return events.snapshot(player, Action.SNAPSHOT_JOIN, "");
        }
        setOnline(player);
        followUps.add(() -> events.snapshot(player, Action.SNAPSHOT_JOIN, ""));
        return events.session(player, Action.JOIN);
    }

    /**
     * A player leaves: their inventory, then the QUIT; or, with {@code quitFirst}, the QUIT and then the
     * inventory. Only while another player stays online; otherwise someone joins instead.
     */
    private @NotNull PendingEvent quit(boolean quitFirst) {
        if (online.size() < 2) {
            return join(false);
        }
        FakePlayer player = online.remove(random.nextInt(online.size()));
        if (quitFirst) {
            setOffline(player);
            followUps.add(() -> events.snapshot(player, Action.SNAPSHOT_QUIT, ""));
            return events.session(player, Action.QUIT);
        }
        // Leaving: neither online nor offline until the QUIT, so nobody picks them in between.
        followUps.add(() -> {
            setOffline(player);
            return events.session(player, Action.QUIT);
        });
        return events.snapshot(player, Action.SNAPSHOT_QUIT, "");
    }

    /** A player dies: their inventory with the death message, then the kill; they respawn at home. */
    private @NotNull PendingEvent death() {
        FakePlayer victim = active();
        int world = victim.world();
        int x = victim.x();
        int y = victim.y();
        int z = victim.z();
        FakePlayer other = online.get(random.nextInt(online.size()));
        Optional<Actor> killer = other != victim && random.nextInt(3) == 0 ? Optional.of(other.actor())
                : Optional.empty();
        String message = FakeContent.deathMessage(random, victim.actor().name(), other.actor().name());
        PendingEvent snapshot = events.snapshot(victim, Action.SNAPSHOT_DEATH, message);
        victim.goHome();
        followUps.add(() -> events.playerKill(world, x, y, z, killer.filter(actor -> stillOnline(other))));
        return snapshot;
    }

    /**
     * A player teleports: their inventory at the place they leave, saying where to; or, for a world change
     * without a teleport, at the place they arrive, saying where from.
     */
    private @NotNull PendingEvent teleport() {
        FakePlayer player = active();
        int from = player.world();
        int to = worlds.size() > 1 && random.nextInt(10) < 3 ? random.nextInt(worlds.size()) : from;
        DevWorld target = worlds.get(to);
        int range = Math.max(16, spread / (to == 0 ? 1 : 2));
        int x = (to == 0 ? player.homeX() : target.x()) + random.nextInt(-range, range + 1);
        int z = (to == 0 ? player.homeZ() : target.z()) + random.nextInt(-range, range + 1);
        int y = Math.clamp(target.y() + random.nextInt(-8, 17), target.minY(), target.maxY());
        if (to != from && random.nextInt(4) == 0) {
            String left = worlds.get(from).name();
            player.moveTo(to, x, y, z);
            return events.snapshot(player, Action.SNAPSHOT_TELEPORT, "world_change from " + left);
        }
        String cause = FakeContent.TELEPORT_CAUSES.get(random.nextInt(FakeContent.TELEPORT_CAUSES.size()));
        PendingEvent snapshot = events.snapshot(player, Action.SNAPSHOT_TELEPORT,
                cause + " to " + target.name() + " " + x + " " + y + " " + z);
        player.moveTo(to, x, y, z);
        return snapshot;
    }

    /** Starts an explosion of 8 to 40 blocks near a player and gives its first block. */
    private @NotNull PendingEvent explosion() {
        FakePlayer near = active();
        burstLeft = 8 + random.nextInt(33);
        burstPlayer = random.nextInt(10) < 4 ? near : null;
        burstSource = BURST_SOURCES[random.nextInt(BURST_SOURCES.length)];
        burstWorld = near.world();
        burstX = near.x() + random.nextInt(-16, 17);
        burstY = near.y();
        burstZ = near.z() + random.nextInt(-16, 17);
        return burst();
    }

    private @NotNull PendingEvent burst() {
        burstLeft--;
        FakePlayer player = burstPlayer;
        Actor actor = player != null && player.online() ? player.actor() : burstSource;
        return events.burstBreak(burstWorld, burstX, burstY, burstZ, actor);
    }

    /** A random online player. */
    private @NotNull FakePlayer active() {
        return online.get(random.nextInt(online.size()));
    }

    /** Another player's name, for commands and inventories that name one. */
    private @NotNull String someone() {
        return players[random.nextInt(players.length)].actor().name();
    }

    private boolean stillOnline(@NotNull FakePlayer player) {
        return player.online();
    }

    private @NotNull FakePlayer takeOffline() {
        int at = random.nextInt(offline.size());
        FakePlayer player = offline.get(at);
        offline.set(at, offline.getLast());
        offline.removeLast();
        return player;
    }

    private void setOnline(@NotNull FakePlayer player) {
        player.online(true);
        online.add(player);
    }

    private void setOffline(@NotNull FakePlayer player) {
        player.online(false);
        offline.add(player);
    }

    /** What the server does next, by weight out of about 10,000 (an explosion is a burst of 8 to 40 breaks). */
    private enum Kind {
        BREAK(2_700), EXPLOSION(8), NATURAL_BREAK(70), PLACE(2_500), FLOW(200), PISTON_PLACE(40), CHANGE(350),
        NATURAL_CHANGE(100), SIGN(15), INTERACT(500), GROW(700), DEPOSIT(500), WITHDRAW(500), VIRTUAL_DEPOSIT(40),
        VIRTUAL_WITHDRAW(40), DROP(220), PICKUP(300), CRAFT(120), THROW(100), ITEM_BREAK(8), TRADE(30), KILL(300),
        SPAWN(25), REMOVE(15), SHEAR(6), NAME(2), LEASH(4), UNLEASH(4), TAME(3), DYE(3), CHAT(250), COMMAND(180),
        BOOK(2), GAMEMODE(3), JOIN(20), QUIT(20), TELEPORT(30), DEATH(10);

        private static final Kind[] VALUES = values();
        private static final int[] CUMULATIVE = new int[VALUES.length];

        static {
            int total = 0;
            for (int i = 0; i < VALUES.length; i++) {
                total += VALUES[i].weight;
                CUMULATIVE[i] = total;
            }
        }

        private final int weight;

        Kind(int weight) {
            this.weight = weight;
        }

        private static @NotNull Kind pick(@NotNull SplittableRandom random) {
            int ticket = random.nextInt(CUMULATIVE[CUMULATIVE.length - 1]);
            for (int i = 0; i < CUMULATIVE.length; i++) {
                if (ticket < CUMULATIVE[i]) {
                    return VALUES[i];
                }
            }
            throw new IllegalStateException("weights do not cover " + ticket);
        }
    }
}
