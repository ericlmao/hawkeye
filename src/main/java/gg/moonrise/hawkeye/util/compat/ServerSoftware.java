package gg.moonrise.hawkeye.util.compat;

import java.util.function.Predicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The server software Hawkeye runs on, told apart by a server class only that software ships (never by an API
 * version). Forks carry their upstream's classes too, so the most specific one is tested first: Folia (and its
 * forks, whose threading is Folia's), then Leaf, Gale, Purpur, Pufferfish, Paper and Spigot.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ServerSoftware {

    FOLIA("Folia", "io.papermc.paper.threadedregions.RegionizedServer"),
    LEAF("Leaf", "org.dreeam.leaf.config.LeafConfig"),
    GALE("Gale", "org.galemc.gale.configuration.GaleGlobalConfiguration"),
    PURPUR("Purpur", "org.purpurmc.purpur.PurpurConfig"),
    PUFFERFISH("Pufferfish", "gg.pufferfish.pufferfish.PufferfishConfig"),
    PAPER("Paper", "io.papermc.paper.configuration.GlobalConfiguration"),
    SPIGOT("Spigot", "org.spigotmc.SpigotConfig"),
    UNKNOWN("unknown", "");

    /** The name shown to operators. */
    private final @NotNull String displayName;

    /** The server class that identifies this software; empty for {@link #UNKNOWN}. */
    private final @NotNull String markerClass;

    /**
     * The most specific software whose marker class is present.
     *
     * @param classPresent whether the server has a class of that name
     * @return the software, {@link #UNKNOWN} when no marker is present
     */
    public static @NotNull ServerSoftware detect(@NotNull Predicate<String> classPresent) {
        for (ServerSoftware software : values()) {
            if (software != UNKNOWN && classPresent.test(software.markerClass)) {
                return software;
            }
        }
        return UNKNOWN;
    }

    /**
     * The most specific software whose marker class the given class loader can find. Nothing is initialized.
     *
     * @param loader the server's class loader
     * @return the software, {@link #UNKNOWN} when no marker is present
     */
    public static @NotNull ServerSoftware detect(@NotNull ClassLoader loader) {
        return detect(name -> {
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
    }
}
