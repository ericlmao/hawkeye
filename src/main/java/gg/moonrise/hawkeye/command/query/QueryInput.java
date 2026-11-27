package gg.moonrise.hawkeye.command.query;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.message.QueryMessages;
import gg.moonrise.hawkeye.core.service.query.syntax.ActionAliases;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.syntax.ParseResult;
import gg.moonrise.hawkeye.core.service.query.QueryBuilder;
import gg.moonrise.hawkeye.core.service.query.QueryContext;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryError;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryFlag;
import gg.moonrise.hawkeye.core.service.query.syntax.QuerySyntax;
import gg.moonrise.hawkeye.core.service.query.catalog.TypeCatalog;
import gg.moonrise.hawkeye.util.text.Completions;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Reads a command's parameter list for a sender: parses it, builds the query with the command's
 * defaults, and on failure tells the sender why (from {@code messages.yml}). Also suggests the next
 * token for tab completion. Shared by lookup, near, rollback, restore and purge.
 */
@SpringComponent
@RequiredArgsConstructor
public final class QueryInput {

    /** The {@code @Suggestions} name of every parameter-list argument. */
    public static final String SUGGESTIONS = "hawkeye-parameters";

    private static final int MAX_TYPE_SUGGESTIONS = 50;

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull QuerySyntax syntax;
    private final @NotNull QueryBuilder builder;
    private final @NotNull TypeCatalog types;

    /**
     * Parses and builds a query, telling the sender what is wrong when it fails.
     *
     * @param sender        who ran the command
     * @param input         the parameter list
     * @param defaultRadius the radius without {@code r:}; 0 means every world
     * @param maxRadius     the largest {@code r:} for players; 0 means no limit (the console is never limited)
     * @return the query, or empty when the sender was told why not
     */
    public @NotNull Optional<BuildResult.Built> read(@NotNull CommandSender sender, @NotNull String input,
            int defaultRadius, int maxRadius) {
        return switch (build(sender, input, defaultRadius, maxRadius)) {
            case BuildResult.Built built -> Optional.of(built);
            case BuildResult.Invalid invalid -> {
                tell(sender, invalid.error());
                yield Optional.empty();
            }
        };
    }

    /**
     * Parses and builds a query without telling the sender anything.
     *
     * @param sender        who ran the command
     * @param input         the parameter list
     * @param defaultRadius the radius without {@code r:}; 0 means every world
     * @param maxRadius     the largest {@code r:} for players; 0 means no limit (the console is never limited)
     * @return the query, or the first error
     */
    public @NotNull BuildResult build(@NotNull CommandSender sender, @NotNull String input, int defaultRadius,
            int maxRadius) {
        return switch (syntax.parse(input)) {
            case ParseResult.Parsed parsed -> builder.build(parsed.parameters(), context(sender, defaultRadius,
                    maxRadius));
            case ParseResult.Invalid invalid -> new BuildResult.Invalid(invalid.error());
        };
    }

    /**
     * Sends a query error to a sender.
     *
     * @param sender the sender
     * @param error  the error
     */
    public void tell(@NotNull CommandSender sender, @NotNull QueryError error) {
        Message.of(template(error.kind())).send(sender, placeholder(error));
    }

    /**
     * A query error as a component: the message {@link #tell} sends, rendered for no viewer in particular.
     *
     * @param error the error
     * @return the message
     */
    public @NotNull Component describe(@NotNull QueryError error) {
        return Message.of(template(error.kind())).asComponent(placeholder(error));
    }

    /**
     * Suggestions for a parameter list: each suggestion is the whole input with its last token
     * completed, so they work for a greedy argument.
     *
     * @param sender who is typing
     * @param input  the parameter list typed so far
     * @return the suggestions
     */
    public @NotNull List<String> suggest(@NotNull CommandSender sender, @NotNull String input) {
        int split = input.lastIndexOf(' ') + 1;
        String head = input.substring(0, split);
        String last = input.substring(split);
        int colon = last.indexOf(':');
        List<String> completions = new ArrayList<>();
        if (colon < 0) {
            QuerySyntax.PREFIXES.forEach(completions::add);
            for (QueryFlag flag : QueryFlag.values()) {
                completions.add(flag.token());
            }
            return Completions.containing(completions, last).stream().map(token -> head + token).toList();
        }
        String prefix = last.substring(0, colon + 1);
        String values = last.substring(colon + 1);
        int comma = values.lastIndexOf(',') + 1;
        String done = prefix + values.substring(0, comma);
        String partial = values.substring(comma);
        List<String> candidates = switch (prefix.toLowerCase(Locale.ROOT)) {
            case "u:", "e:" -> plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            case "a:" -> ActionAliases.names();
            case "t:" -> List.of("30m", "1h", "6h", "1d", "3d", "1w");
            case "r:" -> radii();
            case "i:" -> types.names();
            case "s:" -> sender.hasPermission(Permissions.NETWORK) ? List.of("#network") : List.of();
            default -> List.of();
        };
        return Completions.containing(candidates, partial).stream()
                .limit(MAX_TYPE_SUGGESTIONS)
                .map(candidate -> head + done + candidate)
                .toList();
    }

    private @NotNull List<String> radii() {
        List<String> radii = new ArrayList<>(List.of("5", "10", "25", "50", "#global"));
        plugin.getServer().getWorlds().forEach(world -> radii.add("#" + world.getName()));
        return radii;
    }

    private @NotNull QueryContext context(@NotNull CommandSender sender, int defaultRadius, int maxRadius) {
        QueryContext.SenderPosition position = null;
        if (sender instanceof Entity entity) {
            Location location = entity.getLocation();
            position = new QueryContext.SenderPosition(location.getWorld().getName(), location.getBlockX(),
                    location.getBlockY(), location.getBlockZ());
        }
        boolean console = !(sender instanceof Player);
        return new QueryContext(position, sender.hasPermission(Permissions.NETWORK), position == null ? 0 : defaultRadius,
                console ? 0 : maxRadius, world -> plugin.getServer().getWorld(world) != null);
    }

    private static @NotNull TagResolver.Single placeholder(@NotNull QueryError error) {
        return Placeholder.unparsed("value", error.value());
    }

    private @NotNull String template(@NotNull QueryError.Kind kind) {
        QueryMessages messages = configProvider.messages().getQuery();
        return switch (kind) {
            case UNKNOWN_PARAMETER -> messages.getUnknownParameter();
            case INVALID_TIME -> messages.getInvalidTime();
            case INVALID_RADIUS -> messages.getInvalidRadius();
            case UNKNOWN_ACTION -> messages.getUnknownAction();
            case UNKNOWN_TYPE -> messages.getUnknownType();
            case INVALID_SERVER -> messages.getInvalidServer();
            case EMPTY_VALUE -> messages.getEmptyValue();
            case RADIUS_TOO_LARGE -> messages.getRadiusTooLarge();
            case RADIUS_NEEDS_POSITION -> messages.getRadiusNeedsPosition();
            case UNKNOWN_WORLD -> messages.getUnknownWorld();
            case NETWORK_NOT_ALLOWED -> messages.getNetworkNotAllowed();
            case AREA_WITH_SERVERS -> messages.getAreaWithServers();
        };
    }
}
