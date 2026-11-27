package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.LookupDialogMessages;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.lookup.page.PageView;
import gg.moonrise.hawkeye.ui.dialog.DialogAnswers;
import gg.moonrise.hawkeye.ui.dialog.DialogScreen;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Lays out the lookup dialog from {@code messages.yml}: the results screen (a page of the same lines the chat
 * shows, with buttons to turn pages and open the filters) and the filter form (one field per parameter, with
 * whatever the last lookup said above it). A pure function of the messages and the {@link LookupLines} it
 * renders events with; {@link Actions} says what the buttons do.
 *
 * @param messages the dialog's messages
 * @param lines    the chat renderer whose lines the dialog shows
 */
public record LookupDialogLayout(@NotNull LookupDialogMessages messages, @NotNull LookupLines lines) {

    /** How wide the body lines and fields are drawn. */
    public static final int WIDTH = 300;

    /** The most characters a list field (players, actions, types, servers) takes. */
    private static final int LIST_LENGTH = 256;

    /** The most characters the time field takes. */
    private static final int TIME_LENGTH = 32;

    /** The most characters the radius field takes. */
    private static final int RADIUS_LENGTH = 64;

    /** What the dialog's buttons do; each call gives a fresh handler. */
    public interface Actions {

        /**
         * Shows another page of the lookup.
         *
         * @param page the page to show, from 1
         * @return the button's handler
         */
        @NotNull Consumer<DialogAnswers> turn(int page);

        /**
         * Shows the filter form for the current filters.
         *
         * @return the button's handler
         */
        @NotNull Consumer<DialogAnswers> filters();

        /**
         * Runs the lookup the filter form's answers describe.
         *
         * @return the button's handler
         */
        @NotNull Consumer<DialogAnswers> search();
    }

    /**
     * A page of results: the summary, one line per event, the shards that could not be read; buttons to the
     * previous and next page (when there are) and to the filters.
     *
     * @param view    the page, with at least one event
     * @param filters the filters that found it
     * @param actions what the buttons do
     * @return the screen
     */
    public @NotNull DialogScreen results(@NotNull PageView view, @NotNull LookupFilters filters,
            @NotNull Actions actions) {
        List<Component> body = new ArrayList<>();
        body.add(summary(view, filters));
        view.events().forEach(event -> body.add(lines.events().line(event)));
        body.addAll(lines.warnings(view.warnings()));
        List<DialogScreen.Press> buttons = new ArrayList<>();
        if (view.page() > 1) {
            buttons.add(new DialogScreen.Press(turn(messages.getPrevious(), view.page() - 1),
                    actions.turn(view.page() - 1)));
        }
        if (view.hasMore()) {
            buttons.add(new DialogScreen.Press(turn(messages.getNext(), view.page() + 1),
                    actions.turn(view.page() + 1)));
        }
        buttons.add(new DialogScreen.Press(render(messages.getFilters()), actions.filters()));
        return new DialogScreen(render(messages.getTitle()), body, WIDTH, List.of(),
                new DialogScreen.Buttons.Listed(buttons, new DialogScreen.Close(render(messages.getClose()))));
    }

    /**
     * The filter form: the notice, the hint, one field per parameter filled from {@code filters} (servers only
     * for {@code network}), the count checkbox; Search and Close.
     *
     * @param notice  lines above the hint, such as why the last search found nothing; may be empty
     * @param filters what the fields start with
     * @param network whether the player may search other servers (permission {@code hawkeye.network})
     * @param actions what the buttons do
     * @return the screen
     */
    public @NotNull DialogScreen form(@NotNull List<Component> notice, @NotNull LookupFilters filters,
            boolean network, @NotNull Actions actions) {
        List<Component> body = new ArrayList<>(notice);
        body.add(render(messages.getHint()));
        List<DialogScreen.Field> fields = new ArrayList<>();
        fields.add(text(LookupFilters.PLAYERS, messages.getPlayers(), filters.players(), LIST_LENGTH));
        fields.add(text(LookupFilters.TIME, messages.getTime(), filters.time(), TIME_LENGTH));
        fields.add(text(LookupFilters.RADIUS, messages.getRadius(), filters.radius(), RADIUS_LENGTH));
        fields.add(text(LookupFilters.ACTIONS, messages.getActions(), filters.actions(), LIST_LENGTH));
        fields.add(text(LookupFilters.INCLUDE, messages.getInclude(), filters.include(), LIST_LENGTH));
        fields.add(text(LookupFilters.EXCLUDE, messages.getExclude(), filters.exclude(), LIST_LENGTH));
        if (network) {
            fields.add(text(LookupFilters.SERVERS, messages.getServers(), filters.servers(), LIST_LENGTH));
        }
        fields.add(new DialogScreen.Field.Toggle(LookupFilters.COUNT, render(messages.getCountOnly()),
                filters.count()));
        DialogScreen.Press search = new DialogScreen.Press(render(messages.getSearch()), actions.search());
        return new DialogScreen(render(messages.getFiltersTitle()), body, WIDTH, fields,
                new DialogScreen.Buttons.Choice(search, new DialogScreen.Close(render(messages.getClose()))));
    }

    /**
     * The screen for opening or turning a page: the results when there are events, otherwise the filter form
     * with the chat's reply (nothing found, no session, out of range, the lookup could not run) as its notice.
     *
     * @param result  the page or why there is none
     * @param filters the filters that produced it
     * @param network whether the player may search other servers
     * @param actions what the buttons do
     * @return the screen
     */
    public @NotNull DialogScreen page(@NotNull PageResult result, @NotNull LookupFilters filters, boolean network,
            @NotNull Actions actions) {
        return switch (result) {
            case PageResult.Shown shown when !shown.view().events().isEmpty() -> results(shown.view(), filters,
                    actions);
            case PageResult.Shown shown -> form(lines.page(shown), filters, network, actions);
            case PageResult.NoSession noSession -> form(lines.page(noSession), filters, network, actions);
            case PageResult.OutOfRange outOfRange -> form(lines.page(outOfRange), filters, network, actions);
            case PageResult.Unavailable unavailable -> form(lines.page(unavailable), filters, network, actions);
        };
    }

    /**
     * The screen for a {@code #count} lookup: the filter form with the count (or why there is none) as its
     * notice.
     *
     * @param outcome the count or why there is none
     * @param filters the filters that produced it
     * @param network whether the player may search other servers
     * @param actions what the buttons do
     * @return the screen
     */
    public @NotNull DialogScreen count(@NotNull LookupOutcome outcome, @NotNull LookupFilters filters,
            boolean network, @NotNull Actions actions) {
        return form(lines.count(outcome), filters, network, actions);
    }

    private @NotNull Component summary(@NotNull PageView view, @NotNull LookupFilters filters) {
        TagResolver.Single[] paging = lines.paging(view);
        TagResolver.Single[] resolvers = Arrays.copyOf(paging, paging.length + 1);
        resolvers[paging.length] = Placeholder.unparsed("filters", filters.parameters());
        return Message.of(messages.getSummary()).asComponent(resolvers);
    }

    private static @NotNull Component turn(@NotNull String template, int page) {
        return Message.of(template).asComponent(Placeholder.unparsed("page", Integer.toString(page)));
    }

    private static @NotNull DialogScreen.Field text(@NotNull String key, @NotNull String label,
            @NotNull String initial, int maxLength) {
        return new DialogScreen.Field.Text(key, render(label), initial, maxLength);
    }

    private static @NotNull Component render(@NotNull String template) {
        return Message.of(template).asComponent();
    }
}
