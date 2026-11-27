package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.hawkeye.command.query.QueryInput;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.lookup.page.LookupPager;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryFlag;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.ui.dialog.DialogAnswers;
import gg.moonrise.hawkeye.ui.dialog.DialogScreen;
import gg.moonrise.hawkeye.ui.dialog.DialogScreens;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Shows {@code /hawkeye lookup} and {@code near} to a player as a dialog (config.yml
 * {@code experimental.dialog-lookup}) and runs its buttons: turning pages, opening the filter form and
 * searching with it. Every press closes the dialog; the work runs on the player's thread and ends in a fresh
 * dialog. A button's handler keeps only the player, the filters and a page number. When a dialog cannot be
 * shown, the player gets the chat lines instead.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class LookupDialogs {

    private final @NotNull QueryInput queryInput;
    private final @NotNull LookupPager pager;
    private final @NotNull LookupService lookupService;
    private final @NotNull LookupReplies replies;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ServerThreads threads;
    private final @NotNull DialogScreens screens;

    /**
     * Whether a lookup by this sender is answered with a dialog: a player, with the switch on, on a server that
     * can show dialogs.
     *
     * @param sender who ran the lookup
     * @return true to call {@link #open}, false to answer in chat
     */
    public boolean opensFor(@NotNull CommandSender sender) {
        return sender instanceof Player && configProvider.get().experimental().dialogLookup() && screens.supported();
    }

    /**
     * Runs a lookup and shows its first page (or its count) once it completes.
     *
     * @param player  who asked
     * @param built   the query
     * @param filters the fields the query was built from, for the filter form
     */
    public void open(@NotNull Player player, @NotNull BuildResult.Built built, @NotNull LookupFilters filters) {
        if (built.parameters().has(QueryFlag.COUNT)) {
            lookupService.count(built.query()).whenComplete((count, error) -> {
                LookupOutcome shown = error == null ? count : LookupReplies.failed(error);
                threads.runFor(player, () -> showCount(player, shown, filters));
            });
            return;
        }
        showWhenDone(player, pager.open(player, built.query(), configProvider.get().lookup().pageSize()), filters);
    }

    private void turn(@NotNull Player player, int page, @NotNull LookupFilters filters) {
        showWhenDone(player, pager.page(player, page), filters);
    }

    private void search(@NotNull Player player, @NotNull LookupFilters typed) {
        int maxRadius = configProvider.get().lookup().maxRadius();
        switch (queryInput.build(player, typed.parameters(), 0, maxRadius)) {
            case BuildResult.Built built -> open(player, built, typed);
            case BuildResult.Invalid invalid -> showForm(player, List.of(queryInput.describe(invalid.error())), typed);
        }
    }

    private void showWhenDone(@NotNull Player player, @NotNull CompletableFuture<PageResult> result,
            @NotNull LookupFilters filters) {
        result.whenComplete((page, error) -> {
            PageResult shown = error == null ? page : new PageResult.Unavailable(LookupReplies.failed(error));
            threads.runFor(player, () -> showPage(player, shown, filters));
        });
    }

    private void showPage(@NotNull Player player, @NotNull PageResult result, @NotNull LookupFilters filters) {
        LookupDialogLayout layout = layout();
        show(player, layout.page(result, filters, network(player), actions(player, filters)),
                layout.lines().page(result));
    }

    private void showCount(@NotNull Player player, @NotNull LookupOutcome outcome, @NotNull LookupFilters filters) {
        LookupDialogLayout layout = layout();
        show(player, layout.count(outcome, filters, network(player), actions(player, filters)),
                layout.lines().count(outcome));
    }

    private void showForm(@NotNull Player player, @NotNull List<Component> notice, @NotNull LookupFilters filters) {
        show(player, layout().form(notice, filters, network(player), actions(player, filters)), notice);
    }

    /** Shows a screen, or sends {@code fallback} to chat when the dialog could not be shown. */
    private void show(@NotNull Player player, @NotNull DialogScreen screen, @NotNull List<Component> fallback) {
        if (!screens.show(player, screen)) {
            log.debug("Sending {} the lookup in chat: the dialog could not be shown", player.getName());
            fallback.forEach(player::sendMessage);
        }
    }

    /** The layout for the active messages and the current time. */
    private @NotNull LookupDialogLayout layout() {
        return new LookupDialogLayout(configProvider.messages().getLookupDialog(), replies.lines());
    }

    private @NotNull LookupDialogLayout.Actions actions(@NotNull Player player, @NotNull LookupFilters filters) {
        return new ButtonActions(this, player, filters);
    }

    /**
     * A button's handler: hops to the player's thread (the press arrives on the thread that handled the packet),
     * then does nothing when the player lost the lookup permission while the dialog was open.
     */
    private @NotNull Consumer<DialogAnswers> pressed(@NotNull Player player, @NotNull Consumer<DialogAnswers> work) {
        return answers -> threads.runFor(player, () -> {
            if (player.hasPermission(Permissions.LOOKUP)) {
                work.accept(answers);
            }
        });
    }

    private static boolean network(@NotNull Player player) {
        return player.hasPermission(Permissions.NETWORK);
    }

    /**
     * What the buttons of one dialog do.
     *
     * @param dialogs the controller
     * @param player  who sees the dialog
     * @param current the filters that produced it
     */
    private record ButtonActions(@NotNull LookupDialogs dialogs, @NotNull Player player,
            @NotNull LookupFilters current) implements LookupDialogLayout.Actions {

        @Override
        public @NotNull Consumer<DialogAnswers> turn(int page) {
            return dialogs.pressed(player, answers -> dialogs.turn(player, page, current));
        }

        @Override
        public @NotNull Consumer<DialogAnswers> filters() {
            return dialogs.pressed(player, answers -> dialogs.showForm(player, List.of(), current));
        }

        @Override
        public @NotNull Consumer<DialogAnswers> search() {
            return dialogs.pressed(player, answers -> dialogs.search(player, LookupFilters.of(answers)));
        }
    }
}
