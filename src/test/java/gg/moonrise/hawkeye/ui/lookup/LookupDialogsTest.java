package gg.moonrise.hawkeye.ui.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.command.query.QueryInput;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.lookup.LookupFixtures;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.lookup.page.LookupPager;
import gg.moonrise.hawkeye.core.service.query.BuildResult;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.support.dialog.DialogPlayer;
import gg.moonrise.hawkeye.support.dialog.ShownDialog;
import gg.moonrise.hawkeye.ui.dialog.DialogScreen;
import gg.moonrise.hawkeye.ui.dialog.DialogScreens;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /hawkeye lookup} and {@code near} as a dialog, end to end on a mocked server with the switch on. */
public class LookupDialogsTest {

    private static final String INVALID_SOON = "soon is not a time; use e.g. t:2h, t:3d12h or t:1h-2d.";

    private ServerMock server;
    private TestCommandManager commands;
    private World world;
    private DialogPlayer alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestHawkeyePlugin plugin = TestPlugins.load();
        // Only the events each test records: no join events for the players added below.
        TestPlugins.writeConfig(plugin, "logging:\n  sessions: false\n  join-snapshots: false\n"
                + "experimental:\n  dialog-lookup: true\n");
        TestPlugins.reload();
        TestPlugins.awaitStorage();
        commands = TestPlugins.commands();
        world = server.addSimpleWorld("world");
        alex = new DialogPlayer(server, "Alex");
        server.addPlayer(alex);
        alex.setOp(true);
        alex.setLocation(new Location(world, 0, 64, 0));
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aLookupShowsTheResultsDialogInsteadOfChatLines() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);

        commands.run(alex, "hawkeye lookup");
        ShownDialog results = alex.awaitDialog(server);

        assertFalse(results.isChoice());
        assertEquals("Hawkeye results", EventLinesTest.plain(results.title()));
        List<String> body = results.plainBody();
        assertEquals(4, body.size(), body.toString());
        assertEquals("Page 1/1, 3 events", body.getFirst().strip());
        assertTrue(body.subList(1, 4).stream().allMatch(line -> line.contains("Alex broke stone")), body.toString());
        assertEquals(List.of("/hawkeye tp world 2 64 0"), EventLinesTest.commands(results.body().get(1)));
        assertEquals(List.of("Filters"), results.buttons());
        assertEquals("Close", results.exit());
        assertTrue(AwaitReplies.during(server, alex, 200).isEmpty());
        assertEquals(1, alex.dialogCount());
    }

    @Test
    public void theButtonsTurnPagesAndATypedPageCommandStillAnswersInChat() {
        LookupFixtures.breaks(Actors.NATURE, world, 20);

        commands.run(alex, "hawkeye lookup");
        ShownDialog first = alex.awaitDialog(server);
        assertEquals(List.of("Next >", "Filters"), first.buttons());
        assertTrue(first.plainBody().getFirst().startsWith("Page 1/3, 20 events"), first.plainBody().getFirst());

        ShownDialog second = press(first, "Next >", Map.of(), Map.of());
        assertEquals(List.of("< Previous", "Next >", "Filters"), second.buttons());
        assertTrue(second.plainBody().getFirst().startsWith("Page 2/3"), second.plainBody().getFirst());
        assertTrue(second.plainBody().get(1).endsWith("(11/64/0/world)"), second.plainBody().get(1));

        ShownDialog third = press(second, "Next >", Map.of(), Map.of());
        assertEquals(List.of("< Previous", "Filters"), third.buttons());
        assertEquals(5, third.plainBody().size(), third.plainBody().toString());

        ShownDialog back = press(press(third, "< Previous", Map.of(), Map.of()), "< Previous", Map.of(), Map.of());
        assertEquals(List.of("Next >", "Filters"), back.buttons());
        assertTrue(back.plainBody().getFirst().startsWith("Page 1/3"), back.plainBody().getFirst());
        assertTrue(back.plainBody().get(1).endsWith("(19/64/0/world)"), back.plainBody().get(1));

        int shown = alex.dialogCount();
        commands.run(alex, "hawkeye page 2");
        List<String> chat = AwaitReplies.reply(server, alex);
        assertEquals("Page 2/3 [< prev] [next >]", chat.getLast());
        assertEquals(shown, alex.dialogCount());
    }

    @Test
    public void theFiltersButtonOpensTheFormFilledFromTheLookup() {
        LookupFixtures.breaks(Actors.of(alex), world, 2);

        commands.run(alex, "hawkeye lookup u:alex t:1h");
        ShownDialog form = press(alex.awaitDialog(server), "Filters", Map.of(), Map.of());

        assertTrue(form.isChoice());
        assertEquals("Hawkeye filters", EventLinesTest.plain(form.title()));
        assertEquals(List.of("Search"), form.buttons());
        assertEquals("Close", form.exit());
        assertEquals("alex", form.textInitials().get(LookupFilters.PLAYERS));
        assertEquals("1h", form.textInitials().get(LookupFilters.TIME));
        assertEquals("", form.textInitials().get(LookupFilters.RADIUS));
        assertTrue(form.textInitials().containsKey(LookupFilters.SERVERS), "an operator may search the network");
        assertEquals(Map.of(LookupFilters.COUNT, false), form.toggleInitials());
        assertEquals(List.of("Leave a field empty to not filter by it. Separate several values with commas or "
                + "spaces."), form.plainBody());
    }

    @Test
    public void searchShowsTheResultsForTheTypedFields() {
        PlayerMock steve = server.addPlayer("Steve");
        LookupFixtures.breaks(Actors.of(alex), world, 2);
        LookupFixtures.breaks(Actors.of(steve), world, 3);

        commands.run(alex, "hawkeye lookup u:alex");
        ShownDialog form = press(alex.awaitDialog(server), "Filters", Map.of(), Map.of());
        ShownDialog results = press(form, "Search", fields(Map.of(LookupFilters.PLAYERS, "Steve")),
                Map.of(LookupFilters.COUNT, false));

        assertFalse(results.isChoice());
        List<String> body = results.plainBody();
        assertEquals(4, body.size(), body.toString());
        assertTrue(body.subList(1, 4).stream().allMatch(line -> line.contains("Steve broke stone")), body.toString());
        assertTrue(body.getFirst().contains("u:Steve"), body.getFirst());

        ShownDialog filled = press(results, "Filters", Map.of(), Map.of());
        assertEquals("Steve", filled.textInitials().get(LookupFilters.PLAYERS));
    }

    @Test
    public void anInvalidSearchShowsTheFormAgainWithTheErrorAndTheTypedFields() {
        commands.run(alex, "hawkeye lookup u:nobody");
        ShownDialog form = alex.awaitDialog(server);

        ShownDialog again = press(form, "Search", fields(Map.of(LookupFilters.PLAYERS, "alex",
                LookupFilters.TIME, "soon")), Map.of(LookupFilters.COUNT, true));

        assertTrue(again.isChoice());
        assertEquals(INVALID_SOON, again.plainBody().getFirst());
        assertEquals("alex", again.textInitials().get(LookupFilters.PLAYERS));
        assertEquals("soon", again.textInitials().get(LookupFilters.TIME));
        assertEquals(Map.of(LookupFilters.COUNT, true), again.toggleInitials());
        assertTrue(TestPlugins.drain(alex).isEmpty());
    }

    @Test
    public void aCountShowsTheFormWithTheCount() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);
        LookupFixtures.breaks(Actors.FIRE, world, 2);

        commands.run(alex, "hawkeye lookup #count");
        ShownDialog counted = alex.awaitDialog(server);
        assertTrue(counted.isChoice());
        assertEquals("Matching events: 5", counted.plainBody().getFirst());
        assertEquals(Map.of(LookupFilters.COUNT, true), counted.toggleInitials());

        ShownDialog byAlex = press(counted, "Search", fields(Map.of(LookupFilters.PLAYERS, "alex")),
                Map.of(LookupFilters.COUNT, true));
        assertTrue(byAlex.isChoice());
        assertEquals("Matching events: 3", byAlex.plainBody().getFirst());
        assertTrue(TestPlugins.drain(alex).isEmpty());
    }

    @Test
    public void nothingFoundShowsTheFormWithTheChatReply() {
        commands.run(alex, "hawkeye lookup u:nobody");
        ShownDialog form = alex.awaitDialog(server);

        assertTrue(form.isChoice());
        assertEquals("No results found.", form.plainBody().getFirst());
        assertEquals("nobody", form.textInitials().get(LookupFilters.PLAYERS));
        assertTrue(TestPlugins.drain(alex).isEmpty());
    }

    @Test
    public void nearFillsTheRadius() {
        LookupFixtures.breaks(Actors.NATURE, world, 5);

        commands.run(alex, "hawkeye near 3");
        ShownDialog results = alex.awaitDialog(server);
        assertEquals(5, results.plainBody().size(), results.plainBody().toString());

        ShownDialog form = press(results, "Filters", Map.of(), Map.of());
        assertEquals("3", form.textInitials().get(LookupFilters.RADIUS));
    }

    @Test
    public void aPressAfterThePermissionWasTakenDoesNothing() {
        LookupFixtures.breaks(Actors.NATURE, world, 20);
        commands.run(alex, "hawkeye lookup");
        ShownDialog first = alex.awaitDialog(server);
        int shown = alex.dialogCount();

        alex.setOp(false);
        first.press("Next >", Map.of(), Map.of());
        first.press("Filters", Map.of(), Map.of());

        assertTrue(AwaitReplies.during(server, alex, 300).isEmpty());
        assertEquals(shown, alex.dialogCount());
    }

    @Test
    public void aDialogThatCannotBeShownFallsBackToChat() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);
        LookupDialogs dialogs = new LookupDialogs(TestPlugins.bean(QueryInput.class),
                TestPlugins.bean(LookupPager.class), TestPlugins.bean(LookupService.class),
                TestPlugins.bean(LookupReplies.class), TestPlugins.bean(HawkeyeConfigProvider.class),
                TestPlugins.bean(ServerThreads.class), new RefusingScreens());
        assertTrue(dialogs.opensFor(alex));
        BuildResult.Built built = assertInstanceOf(BuildResult.Built.class,
                TestPlugins.bean(QueryInput.class).build(alex, "u:alex", 0, 250));

        dialogs.open(alex, built, LookupFilters.of("u:alex"));
        List<String> chat = AwaitReplies.reply(server, alex);

        assertEquals(4, chat.size(), chat.toString());
        assertEquals("----- Hawkeye results -----", chat.getFirst());
        assertTrue(chat.subList(1, 4).stream().allMatch(line -> line.contains("Alex broke stone")), chat.toString());
        assertEquals(0, alex.dialogCount());
    }

    @Test
    public void anInvalidCommandLineIsRefusedInChat() {
        commands.run(alex, "hawkeye lookup t:soon");

        assertEquals(List.of(INVALID_SOON), TestPlugins.drain(alex));
        assertTrue(AwaitReplies.during(server, alex, 200).isEmpty());
        assertEquals(0, alex.dialogCount());
    }

    @Test
    public void theConsoleAndOtherPlayersNeverGetADialog() {
        LookupDialogs dialogs = TestPlugins.bean(LookupDialogs.class);
        assertTrue(dialogs.opensFor(alex));
        assertFalse(dialogs.opensFor(server.getConsoleSender()));
    }

    /** Presses a button and waits for the dialog it leads to. */
    private @NotNull ShownDialog press(@NotNull ShownDialog dialog, @NotNull String label,
            @NotNull Map<String, String> texts, @NotNull Map<String, Boolean> toggles) {
        dialog.press(label, texts, toggles);
        return alex.awaitDialog(server);
    }

    /** What a client sends for the form: every text field, empty unless given. */
    private static @NotNull Map<String, String> fields(@NotNull Map<String, String> typed) {
        Map<String, String> texts = new HashMap<>();
        for (String key : List.of(LookupFilters.PLAYERS, LookupFilters.TIME, LookupFilters.RADIUS,
                LookupFilters.ACTIONS, LookupFilters.INCLUDE, LookupFilters.EXCLUDE, LookupFilters.SERVERS)) {
            texts.put(key, typed.getOrDefault(key, ""));
        }
        return texts;
    }

    /** A server that can show dialogs but refuses this one. */
    private static final class RefusingScreens implements DialogScreens {

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean show(@NotNull Player player, @NotNull DialogScreen screen) {
            return false;
        }
    }
}
