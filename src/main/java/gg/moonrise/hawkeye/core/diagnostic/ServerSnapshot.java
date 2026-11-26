package gg.moonrise.hawkeye.core.diagnostic;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The server facts at the top of every error and crash file.
 *
 * @param minecraftVersion the Minecraft version ({@code 1.21.1})
 * @param software         the server software told apart by its classes ({@code Paper}, {@code Folia})
 * @param serverName       the name the server reports ({@code Paper}, {@code Purpur})
 * @param serverVersion    the version the server reports, with its build
 * @param java             the Java version, vendor and VM
 * @param system           the operating system and architecture
 * @param hawkeyeVersion   Hawkeye's version
 * @param plugins          every plugin the server has loaded, by name
 */
public record ServerSnapshot(@NotNull String minecraftVersion, @NotNull String software, @NotNull String serverName,
                             @NotNull String serverVersion, @NotNull String java, @NotNull String system,
                             @NotNull String hawkeyeVersion, @NotNull List<PluginLine> plugins) {

    /**
     * One loaded plugin.
     *
     * @param name    its name
     * @param version its version
     * @param enabled whether it is enabled
     */
    public record PluginLine(@NotNull String name, @NotNull String version, boolean enabled) {
    }
}
