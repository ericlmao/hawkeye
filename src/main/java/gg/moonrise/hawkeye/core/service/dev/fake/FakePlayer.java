package gg.moonrise.hawkeye.core.service.dev.fake;

import gg.moonrise.hawkeye.core.service.dev.DevWorld;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.payload.Payload;
import java.util.List;
import java.util.SplittableRandom;
import org.jetbrains.annotations.NotNull;

/**
 * One fake player's state while fake data is generated: who they are, where their home is, where they stand,
 * what they build with, whether they are online and what they carry. Mutable; used by one generator thread.
 */
public final class FakePlayer {

    private final int index;
    private final @NotNull Actor actor;
    private final int homeX;
    private final int homeY;
    private final int homeZ;
    private int world;
    private int x;
    private int y;
    private int z;
    private String material;
    private boolean online;
    private List<Payload.Slot> inventory;

    /**
     * A player standing at home in the primary world, offline.
     *
     * @param index their index ({@link FakeServer#player(int)})
     * @param actor who they are
     * @param homeX their home x in the primary world
     * @param homeY their home y
     * @param homeZ their home z
     */
    public FakePlayer(int index, @NotNull Actor actor, int homeX, int homeY, int homeZ) {
        this.index = index;
        this.actor = actor;
        this.homeX = homeX;
        this.homeY = homeY;
        this.homeZ = homeZ;
        goHome();
    }

    /**
     * Their index.
     *
     * @return 0 to the player count
     */
    public int index() {
        return index;
    }

    /**
     * Who they are.
     *
     * @return the player actor
     */
    public @NotNull Actor actor() {
        return actor;
    }

    /**
     * The world they are in.
     *
     * @return its index in the generator's world list (0 is the primary world)
     */
    public int world() {
        return world;
    }

    /**
     * Where they stand, x.
     *
     * @return the block x
     */
    public int x() {
        return x;
    }

    /**
     * Where they stand, y.
     *
     * @return the block y
     */
    public int y() {
        return y;
    }

    /**
     * Where they stand, z.
     *
     * @return the block z
     */
    public int z() {
        return z;
    }

    /**
     * Their home x in the primary world.
     *
     * @return the block x
     */
    public int homeX() {
        return homeX;
    }

    /**
     * Their home y in the primary world.
     *
     * @return the block y
     */
    public int homeY() {
        return homeY;
    }

    /**
     * Their home z in the primary world.
     *
     * @return the block z
     */
    public int homeZ() {
        return homeZ;
    }

    /**
     * Whether they are online.
     *
     * @return {@code true} between their join and their quit
     */
    public boolean online() {
        return online;
    }

    /**
     * Marks them online or offline.
     *
     * @param online the new state
     */
    public void online(boolean online) {
        this.online = online;
    }

    /** Puts them back at home in the primary world (a join, a respawn, {@code /home}). */
    public void goHome() {
        moveTo(0, homeX, homeY + 1, homeZ);
    }

    /**
     * Moves them.
     *
     * @param world the world index
     * @param x     the block x
     * @param y     the block y
     * @param z     the block z
     */
    public void moveTo(int world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.material = null;
    }

    /**
     * Mostly a block or two from the last spot, now and then a walk; never further than {@code range} from
     * the anchor and always inside the world's heights.
     *
     * @param random  the random source
     * @param bounds  the world they are in
     * @param anchorX the x they stay around (their home, or the world's centre)
     * @param anchorZ the z they stay around
     * @param range   how far from the anchor they may go
     */
    public void step(@NotNull SplittableRandom random, @NotNull DevWorld bounds, int anchorX, int anchorZ,
            int range) {
        if (random.nextInt(1_000) < 30) {
            x += random.nextInt(-40, 41);
            z += random.nextInt(-40, 41);
            y += random.nextInt(-6, 7);
        } else {
            x += random.nextInt(-2, 3);
            y += random.nextInt(-1, 2);
            z += random.nextInt(-2, 3);
        }
        x = Math.clamp(x, anchorX - range, anchorX + range);
        z = Math.clamp(z, anchorZ - range, anchorZ + range);
        y = Math.clamp(y, bounds.minY(), bounds.maxY());
    }

    /**
     * The block in hand or under the pick: kept for a while, as players mine and build in runs.
     *
     * @param random  the random source
     * @param palette the blocks of the world they are in
     * @return a block state
     */
    public @NotNull String material(@NotNull SplittableRandom random, @NotNull FakePalette palette) {
        if (material == null || random.nextInt(100) < 15) {
            material = palette.pick(random);
        }
        return material;
    }

    /**
     * What they carry now: made up the first time, then changed a little at every call, so one player's
     * snapshots resemble each other.
     *
     * @param random the random source
     * @return the non-empty slots
     */
    public @NotNull List<Payload.Slot> inventory(@NotNull SplittableRandom random) {
        inventory = inventory == null ? FakeInventories.player(random) : FakeInventories.mutate(inventory, random);
        return inventory;
    }
}
