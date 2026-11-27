package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.command.query.QueryInput;
import gg.moonrise.hawkeye.config.file.LookupSettings;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.lookup.page.LookupPager;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryFlag;
import gg.moonrise.hawkeye.ui.lookup.LookupDialogs;
import gg.moonrise.hawkeye.ui.lookup.LookupFilters;
import gg.moonrise.hawkeye.ui.lookup.LookupReplies;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * {@code /hawkeye lookup [parameters]}, {@code near [radius]} and {@code page <page>}. Lookups run on
 * the query pool and the reply reaches the sender on their own thread: in chat, or for a player as a
 * dialog when {@code experimental.dialog-lookup} is on and the server can show one ({@link LookupDialogs}).
 * A parameter list that cannot be read is refused in chat either way, and {@code page} always answers in
 * chat. This class also declares the
 * parameter-list suggestions ({@link QueryInput#SUGGESTIONS}) every other parameter command reuses.
 * Cloud resolves a named suggestion provider when it parses an argument, so this bean is ordered first:
 * the registry parses it before any command that references the name.
 */
@SpringComponent
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public final class LookupCommand implements PaperCommand {

    private final @NotNull QueryInput queryInput;
    private final @NotNull LookupPager pager;
    private final @NotNull LookupService lookupService;
    private final @NotNull LookupReplies replies;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull LookupDialogs dialogs;

    @Command(Permissions.ROOT + " lookup [parameters]")
    @CommandDescription("Searches the history (u: t: r: a: i: e: s: #count)")
    @Permission(Permissions.LOOKUP)
    public void lookup(@NotNull CommandSourceStack source,
            @Argument(value = "parameters", suggestions = QueryInput.SUGGESTIONS) @Greedy @Nullable String parameters) {
        CommandSender sender = source.getSender();
        LookupSettings settings = configProvider.get().lookup();
        String text = parameters == null ? "" : parameters;
        queryInput.read(sender, text, 0, settings.maxRadius())
                .ifPresent(built -> answer(sender, built, text, settings));
    }

    @Command(Permissions.ROOT + " near [radius]")
    @CommandDescription("Shows what happened around you")
    @Permission(Permissions.LOOKUP)
    public void near(@NotNull CommandSourceStack source, @Argument("radius") @Nullable Integer radius) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player)) {
            Message.of(configProvider.messages().getGeneral().getPlayersOnly()).send(sender);
            return;
        }
        LookupSettings settings = configProvider.get().lookup();
        int blocks = radius == null ? settings.nearRadius() : radius;
        String text = "r:" + blocks;
        queryInput.read(sender, text, 0, settings.maxRadius())
                .ifPresent(built -> answer(sender, built, text, settings));
    }

    @Command(Permissions.ROOT + " page <page>")
    @CommandDescription("Shows another page of your last lookup")
    @Permission(Permissions.LOOKUP)
    public void page(@NotNull CommandSourceStack source, @Argument("page") int page) {
        CommandSender sender = source.getSender();
        replies.page(sender, pager.page(sender, page));
    }

    @Suggestions(QueryInput.SUGGESTIONS)
    public @NotNull List<String> parameters(@NotNull CommandContext<CommandSourceStack> context,
            @NotNull CommandInput input) {
        return queryInput.suggest(context.sender().getSender(), input.remainingInput());
    }

    /** Answers a lookup with a dialog when the sender gets one, else in chat. */
    private void answer(@NotNull CommandSender sender, @NotNull BuildResult.Built built, @NotNull String text,
            @NotNull LookupSettings settings) {
        if (sender instanceof Player player && dialogs.opensFor(player)) {
            dialogs.open(player, built, LookupFilters.of(text));
            return;
        }
        run(sender, built, settings);
    }

    private void run(@NotNull CommandSender sender, @NotNull BuildResult.Built built, @NotNull LookupSettings settings) {
        if (built.parameters().has(QueryFlag.COUNT)) {
            replies.count(sender, lookupService.count(built.query()));
            return;
        }
        replies.page(sender, pager.open(sender, built.query(), settings.pageSize()));
    }
}
