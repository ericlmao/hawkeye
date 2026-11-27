package gg.moonrise.hawkeye.ui.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.LookupDialogMessages;
import gg.moonrise.hawkeye.config.message.MessagesConfig;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.service.lookup.LookupWarning;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.lookup.page.PageView;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.ui.dialog.DialogAnswers;
import gg.moonrise.hawkeye.ui.dialog.DialogScreen;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.serialize.ItemDetailsTest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class LookupDialogLayoutTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final LookupQuery EVERYWHERE = LookupQuery.at(new Area.Everywhere());
    private static final LookupFilters FILTERS = new LookupFilters("alex", "1h", "5", "-block", "stone", "#tnt",
            "survival", false);

    private final MessagesConfig messages = new MessagesConfig();
    private final List<Book> opened = new ArrayList<>();
    private final LookupLines lines = new LookupLines(messages, new EventLines(messages.getLookup(),
            messages.getActions(), "survival", NOW, ZoneOffset.UTC, book -> {
                opened.add(book);
                return ClickEvent.runCommand("/read");
            }));
    private final LookupDialogLayout layout = new LookupDialogLayout(messages.getLookupDialog(), lines);
    private final RecordingActions actions = new RecordingActions();

    @Test
    public void theResultsShowTheSummaryTheChatLinesAndTheWarnings() {
        List<StoredEvent> events = List.of(event(0, null), event(1, book()));
        List<LookupWarning> warnings = List.of(new LookupWarning("east", "down"));
        PageView view = new PageView(EVERYWHERE, 1, 2, new LookupTotal.Exact(5), events, true, warnings);

        DialogScreen screen = layout.results(view, FILTERS, actions);

        assertEquals("Hawkeye results", EventLinesTest.plain(screen.title()));
        assertEquals(4, screen.body().size(), screen.body().toString());
        assertEquals("Page 1/3, 5 events u:alex t:1h r:5 a:-block i:stone e:#tnt s:survival",
                EventLinesTest.plain(screen.body().getFirst()));
        assertEquals(lines.events().line(events.get(0)), screen.body().get(1));
        assertEquals(lines.events().line(events.get(1)), screen.body().get(2));
        assertEquals("Shard east could not be read: down", EventLinesTest.plain(screen.body().get(3)));
        assertEquals(List.of("/hawkeye tp world 0 64 0"), EventLinesTest.commands(screen.body().get(1)));
        assertTrue(hovers(screen.body().get(1)).contains("2026-11-16T11:59:59"), hovers(screen.body().get(1))
                .toString());
        assertTrue(EventLinesTest.commands(screen.body().get(2)).contains("/read"),
                EventLinesTest.commands(screen.body().get(2)).toString());
        assertEquals("Diary", EventLinesTest.plain(opened.getLast().title()));
        assertEquals(LookupDialogLayout.WIDTH, screen.width());
        assertEquals(300, screen.width());
        assertTrue(screen.fields().isEmpty());
    }

    @Test
    public void theSummaryShowsTheFiltersAsTypedText() {
        PageView view = new PageView(EVERYWHERE, 1, 8, new LookupTotal.Exact(1), List.of(event(0, null)), false,
                List.of());

        DialogScreen screen = layout.results(view, new LookupFilters("<red>alex", "", "", "", "", "", "", false),
                actions);

        assertEquals("Page 1/1, 1 events u:<red>alex", EventLinesTest.plain(screen.body().getFirst()));
        assertEquals("Page 1/1, 1 events ", EventLinesTest.plain(layout.results(view, LookupFilters.NONE, actions)
                .body().getFirst()));
    }

    @Test
    public void noBodyLineTurnsPagesByCommand() {
        PageView view = new PageView(EVERYWHERE, 2, 2, new LookupTotal.Exact(6), events(2), true, List.of());
        for (Component line : layout.results(view, FILTERS, actions).body()) {
            assertTrue(EventLinesTest.commands(line).stream().noneMatch(command -> command.startsWith("/hawkeye page")),
                    EventLinesTest.commands(line).toString());
        }
    }

    @Test
    public void theFirstPageWithMoreHasNextAndFilters() {
        DialogScreen.Buttons.Listed buttons = listed(new PageView(EVERYWHERE, 1, 2, new LookupTotal.Exact(6),
                events(2), true, List.of()));
        assertEquals(List.of("Next >", "Filters"), labels(buttons));
        assertEquals("Close", EventLinesTest.plain(buttons.exit().label()));
        assertSame(actions.turned.get(2), buttons.actions().get(0).handler());
        assertSame(actions.filters, buttons.actions().get(1).handler());
    }

    @Test
    public void aMiddlePageHasPreviousNextAndFilters() {
        DialogScreen.Buttons.Listed buttons = listed(new PageView(EVERYWHERE, 2, 2, new LookupTotal.Exact(6),
                events(2), true, List.of()));
        assertEquals(List.of("< Previous", "Next >", "Filters"), labels(buttons));
        assertSame(actions.turned.get(1), buttons.actions().get(0).handler());
        assertSame(actions.turned.get(3), buttons.actions().get(1).handler());
        assertSame(actions.filters, buttons.actions().get(2).handler());
    }

    @Test
    public void theLastPageHasPreviousAndFilters() {
        DialogScreen.Buttons.Listed buttons = listed(new PageView(EVERYWHERE, 3, 2, new LookupTotal.Exact(6),
                events(2), false, List.of()));
        assertEquals(List.of("< Previous", "Filters"), labels(buttons));
        assertSame(actions.turned.get(2), buttons.actions().get(0).handler());
    }

    @Test
    public void aSinglePageHasOnlyFilters() {
        DialogScreen.Buttons.Listed buttons = listed(new PageView(EVERYWHERE, 1, 8, new LookupTotal.Exact(2),
                events(2), false, List.of()));
        assertEquals(List.of("Filters"), labels(buttons));
        assertTrue(actions.turned.isEmpty());
    }

    @Test
    public void thePageButtonsNameThePageTheyOpen() throws ReflectiveOperationException {
        LookupDialogMessages custom = new LookupDialogMessages();
        set(custom, "previous", "Back to <page>");
        set(custom, "next", "On to <page>");
        LookupDialogLayout numbered = new LookupDialogLayout(custom, lines);
        DialogScreen screen = numbered.results(new PageView(EVERYWHERE, 2, 2, new LookupTotal.Exact(6), events(2),
                true, List.of()), FILTERS, actions);
        assertEquals(List.of("Back to 1", "On to 3", "Filters"),
                labels(assertInstanceOf(DialogScreen.Buttons.Listed.class, screen.buttons())));
    }

    @Test
    public void theFormHasOneFieldPerParameterFilledFromTheFilters() {
        DialogScreen screen = layout.form(List.of(), FILTERS, true, actions);

        assertEquals("Hawkeye filters", EventLinesTest.plain(screen.title()));
        assertEquals(List.of("Leave a field empty to not filter by it. Separate several values with commas or spaces."),
                screen.body().stream().map(EventLinesTest::plain).toList());
        assertEquals(List.of("players", "time", "radius", "actions", "include", "exclude", "servers", "count"),
                screen.fields().stream().map(DialogScreen.Field::key).toList());
        Map<String, String> initials = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();
        for (DialogScreen.Field field : screen.fields()) {
            if (field instanceof DialogScreen.Field.Text text) {
                initials.put(text.key(), text.initial());
                lengths.put(text.key(), text.maxLength());
            }
        }
        assertEquals(Map.of("players", "alex", "time", "1h", "radius", "5", "actions", "-block", "include", "stone",
                "exclude", "#tnt", "servers", "survival"), initials);
        assertEquals(Map.of("players", 256, "time", 32, "radius", 64, "actions", 256, "include", 256, "exclude", 256,
                "servers", 256), lengths);
        DialogScreen.Field.Toggle count = assertInstanceOf(DialogScreen.Field.Toggle.class, screen.fields().getLast());
        assertFalse(count.initial());
        assertTrue(assertInstanceOf(DialogScreen.Field.Toggle.class, layout.form(List.of(),
                new LookupFilters("", "", "", "", "", "", "", true), false, actions).fields().getLast()).initial());
        DialogScreen.Buttons.Choice buttons = assertInstanceOf(DialogScreen.Buttons.Choice.class, screen.buttons());
        assertEquals("Search", EventLinesTest.plain(buttons.confirm().label()));
        assertSame(actions.search, buttons.confirm().handler());
        assertEquals("Close", EventLinesTest.plain(buttons.cancel().label()));
        assertEquals(LookupDialogLayout.WIDTH, screen.width());
    }

    @Test
    public void theServersFieldNeedsTheNetworkPermission() {
        DialogScreen screen = layout.form(List.of(), FILTERS, false, actions);
        assertEquals(List.of("players", "time", "radius", "actions", "include", "exclude", "count"),
                screen.fields().stream().map(DialogScreen.Field::key).toList());
    }

    @Test
    public void theNoticeComesBeforeTheHint() {
        List<Component> notice = List.of(Component.text("first"), Component.text("second"));
        DialogScreen screen = layout.form(notice, FILTERS, false, actions);
        assertEquals(List.of("first", "second",
                "Leave a field empty to not filter by it. Separate several values with commas or spaces."),
                screen.body().stream().map(EventLinesTest::plain).toList());
    }

    @Test
    public void aPageWithEventsIsTheResults() {
        PageView view = new PageView(EVERYWHERE, 1, 8, new LookupTotal.Exact(2), events(2), false, List.of());
        DialogScreen screen = layout.page(new PageResult.Shown(view), FILTERS, false, actions);
        assertEquals(layout.results(view, FILTERS, actions), screen);
    }

    @Test
    public void everyOtherPageResultIsTheFormWithTheChatReply() {
        List<PageResult> results = List.of(
                new PageResult.Shown(new PageView(EVERYWHERE, 1, 8, new LookupTotal.Exact(0), List.of(), false,
                        List.of())),
                new PageResult.NoSession(),
                new PageResult.OutOfRange(3),
                new PageResult.Unavailable(new LookupOutcome.NotReady()),
                new PageResult.Unavailable(new LookupOutcome.Failed("busy")),
                new PageResult.Unavailable(new LookupOutcome.TimedOut()));
        for (PageResult result : results) {
            DialogScreen screen = layout.page(result, FILTERS, true, actions);
            List<Component> notice = lines.page(result);
            assertEquals(layout.form(notice, FILTERS, true, actions), screen, result.toString());
            assertEquals(notice, screen.body().subList(0, notice.size()), result.toString());
        }
        assertEquals("No results found.", EventLinesTest.plain(layout.page(results.getFirst(), FILTERS, true, actions)
                .body().getFirst()));
    }

    @Test
    public void aCountIsTheFormWithTheCount() {
        LookupOutcome outcome = new LookupOutcome.Count(42, true, List.of());
        DialogScreen screen = layout.count(outcome, FILTERS, false, actions);
        assertEquals(layout.form(lines.count(outcome), FILTERS, false, actions), screen);
        assertEquals("Matching events: 42", EventLinesTest.plain(screen.body().getFirst()));
    }

    @Test
    public void everyDefaultLabelRendersAsPlainText() {
        LookupDialogMessages defaults = messages.getLookupDialog();
        assertEquals("Hawkeye results", plain(defaults.getTitle()));
        assertEquals("< Previous", plain(defaults.getPrevious()));
        assertEquals("Next >", plain(defaults.getNext()));
        assertEquals("Filters", plain(defaults.getFilters()));
        assertEquals("Close", plain(defaults.getClose()));
        assertEquals("Hawkeye filters", plain(defaults.getFiltersTitle()));
        assertEquals("Leave a field empty to not filter by it. Separate several values with commas or spaces.",
                plain(defaults.getHint()));
        assertEquals("Players (u:)", plain(defaults.getPlayers()));
        assertEquals("Time (t:) e.g. 3d or 1h-3d", plain(defaults.getTime()));
        assertEquals("Radius (r:) e.g. 10, 10x5, #global", plain(defaults.getRadius()));
        assertEquals("Actions (a:) e.g. -block, kill", plain(defaults.getActions()));
        assertEquals("Only these types (i:)", plain(defaults.getInclude()));
        assertEquals("Except these (e:)", plain(defaults.getExclude()));
        assertEquals("Servers (s:)", plain(defaults.getServers()));
        assertEquals("Count only (#count)", plain(defaults.getCountOnly()));
        assertEquals("Search", plain(defaults.getSearch()));
    }

    @Test
    public void theFormLabelsTheFieldsFromTheMessages() {
        List<String> labels = layout.form(List.of(), FILTERS, true, actions).fields().stream()
                .map(field -> EventLinesTest.plain(field.label()))
                .toList();
        assertEquals(List.of("Players (u:)", "Time (t:) e.g. 3d or 1h-3d", "Radius (r:) e.g. 10, 10x5, #global",
                "Actions (a:) e.g. -block, kill", "Only these types (i:)", "Except these (e:)", "Servers (s:)",
                "Count only (#count)"), labels);
    }

    private DialogScreen.Buttons.Listed listed(PageView view) {
        return assertInstanceOf(DialogScreen.Buttons.Listed.class, layout.results(view, FILTERS, actions).buttons());
    }

    private static List<String> labels(DialogScreen.Buttons.Listed buttons) {
        return buttons.actions().stream().map(press -> EventLinesTest.plain(press.label())).toList();
    }

    private static String plain(String template) {
        return EventLinesTest.plain(Message.of(template).asComponent());
    }

    private static void set(LookupDialogMessages target, String name, String value)
            throws ReflectiveOperationException {
        Field field = LookupDialogMessages.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<String> hovers(Component component) {
        List<String> hovers = new ArrayList<>();
        walk(component, part -> {
            HoverEvent<?> hover = part.hoverEvent();
            if (hover != null && hover.value() instanceof Component text) {
                hovers.add(EventLinesTest.plain(text));
            }
        });
        return hovers;
    }

    private static void walk(Component component, Consumer<Component> visitor) {
        visitor.accept(component);
        component.children().forEach(child -> walk(child, visitor));
    }

    private static List<StoredEvent> events(int count) {
        return IntStream.range(0, count).mapToObj(i -> event(i, null)).toList();
    }

    private static StoredEvent event(int x, @Nullable Payload.Item item) {
        long millis = NOW.toEpochMilli() - (x + 1) * 1000L;
        Action action = item == null ? Action.BLOCK_BREAK : Action.ITEM_DROP;
        String subject = item == null ? "minecraft:stone" : "minecraft:written_book";
        return new StoredEvent(EventIds.compose(millis, 1, 0), "survival", "world", BlockPositions.pack(x, 64, 0),
                Actor.source("tnt"), action, subject, item == null ? "minecraft:air" : null, item == null ? 0 : 1,
                false, item == null ? null : PayloadCodec.encode(item));
    }

    private static Payload.Item book() {
        return new Payload.Item(ItemDetailsTest.item("minecraft:written_book", ItemDetailsTest.compound(
                "minecraft:written_book_content", ItemDetailsTest.compound(
                        "pages", ItemDetailsTest.list(NbtTag.STRING, ItemDetailsTest.str("Day one")),
                        "title", ItemDetailsTest.compound("raw", ItemDetailsTest.str("Diary")),
                        "author", ItemDetailsTest.str("Alex")))));
    }

    /** Hands out one recorded handler per button, so a test can tell which one a button got. */
    private static final class RecordingActions implements LookupDialogLayout.Actions {

        private final Map<Integer, Consumer<DialogAnswers>> turned = new HashMap<>();
        private final Consumer<DialogAnswers> filters = answers -> {
        };
        private final Consumer<DialogAnswers> search = answers -> {
        };

        @Override
        public Consumer<DialogAnswers> turn(int page) {
            Consumer<DialogAnswers> handler = answers -> {
            };
            turned.put(page, handler);
            return handler;
        }

        @Override
        public Consumer<DialogAnswers> filters() {
            return filters;
        }

        @Override
        public Consumer<DialogAnswers> search() {
            return search;
        }
    }
}
