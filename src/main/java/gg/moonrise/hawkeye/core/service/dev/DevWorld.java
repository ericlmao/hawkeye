package gg.moonrise.hawkeye.core.service.dev;

import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * A world fake data is generated in (dev mode): its name, what kind of world it is, where fake players
 * gather and its build height. The command reads it from the server's worlds on the calling thread, so the
 * generator never touches Bukkit.
 *
 * @param name      the world name, 1 to {@link #MAX_NAME} characters
 * @param dimension which block palette its events use
 * @param x         the centre x (the spawn, or the sender's position)
 * @param y         the centre y, inside {@code minY..maxY}
 * @param z         the centre z
 * @param minY      the lowest block y, inclusive
 * @param maxY      the highest block y, inclusive
 */
public record DevWorld(@NotNull String name, @NotNull Dimension dimension, int x, int y, int z, int minY, int maxY) {

    /** The longest world name. */
    public static final int MAX_NAME = 255;

    /** How far from 0 a centre may lie on x and z (the vanilla world border is 29,999,984). */
    public static final int MAX_HORIZONTAL = 30_000_000;

    /** The worlds of a vanilla server, used when the server reports none. */
    public static final List<DevWorld> DEFAULTS = List.of(
            new DevWorld("world", Dimension.OVERWORLD, 0, 64, 0, -64, 319),
            new DevWorld("world_nether", Dimension.NETHER, 0, 64, 0, 0, 255),
            new DevWorld("world_the_end", Dimension.END, 0, 64, 0, 0, 255));

    /**
     * Checks the name and clamps the heights into what a packed position holds, the centre into the heights
     * and the world border.
     *
     * @param name      the world name
     * @param dimension which block palette its events use
     * @param x         the centre x
     * @param y         the centre y
     * @param z         the centre z
     * @param minY      the lowest block y
     * @param maxY      the highest block y
     */
    public DevWorld {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("a world name needs 1 to " + MAX_NAME + " characters");
        }
        minY = Math.clamp(minY, BlockPositions.MIN_Y, BlockPositions.MAX_Y);
        maxY = Math.clamp(maxY, BlockPositions.MIN_Y, BlockPositions.MAX_Y);
        if (minY > maxY) {
            throw new IllegalArgumentException("the lowest y " + minY + " is above the highest " + maxY);
        }
        y = Math.clamp(y, minY, maxY);
        x = Math.clamp(x, -MAX_HORIZONTAL, MAX_HORIZONTAL);
        z = Math.clamp(z, -MAX_HORIZONTAL, MAX_HORIZONTAL);
    }

    /** Which block palette a world's events use. */
    public enum Dimension {
        /** An overworld (or any world that is not the nether or the end). */
        OVERWORLD,
        /** A nether. */
        NETHER,
        /** An end. */
        END
    }
}
