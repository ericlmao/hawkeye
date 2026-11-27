package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.lookup.LookupFixtures;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestCommandSource;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.support.dialog.DialogPlayer;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import gg.moonrise.moss.paper.MossPaper;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /hawkeye lookup}, {@code near} and {@code page} end to end on a mocked server. */
public class LookupCommandTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSenderMock console;
    private World world;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        // Only the events each test records: no join events for the player added below.
        TestPlugins.writeConfig(plugin, "logging:\n  sessions: false\n  join-snapshots: false\n");
        TestPlugins.reload();
        TestPlugins.awaitStorage();
        commands = TestPlugins.commands();
        console = server.getConsoleSender();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        alex.setOp(true);
        alex.setLocation(new Location(world, 0, 64, 0));
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aLookupListsTheMatchingEvents() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);
        LookupFixtures.breaks(Actors.FIRE, world, 2);

        commands.run(console, "hawkeye lookup u:alex");
        List<String> reply = AwaitReplies.reply(server, console);

        assertEquals(4, reply.size(), reply.toString());
        assertEquals("----- Hawkeye results -----", reply.getFirst());
        assertTrue(reply.subList(1, 4).stream().allMatch(line -> line.contains("Alex broke stone")), reply.toString());
        assertTrue(reply.get(1).endsWith("(2/64/0/world)"), reply.get(1));
    }

    @Test
    public void theAliasAndNoParametersLookUpEverything() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);
        LookupFixtures.breaks(Actors.FIRE, world, 2);

        commands.run(console, "he l");
        assertEquals(6, AwaitReplies.reply(server, console).size());
    }

    @Test
    public void aResultLocationOnThisServerTeleportsWhenClicked() {
        LookupFixtures.breaks(Actors.of(alex), world, 1);

        commands.run(alex, "hawkeye lookup");
        List<Component> reply = AwaitReplies.components(server, alex, 2);

        assertEquals(List.of("/hawkeye tp world 0 64 0"), clicks(reply.get(1)));
    }

    @Test
    public void aCountLookupRepliesWithTheTotal() {
        LookupFixtures.breaks(Actors.of(alex), world, 3);
        LookupFixtures.breaks(Actors.FIRE, world, 2);

        commands.run(console, "hawkeye lookup u:alex #count");
        assertEquals(List.of("Matching events: 3"), AwaitReplies.reply(server, console));
    }

    @Test
    public void invalidParametersAreExplained() {
        commands.run(console, "hawkeye lookup q:stone");
        assertEquals(List.of("Unknown parameter q:stone. Use u: t: r: a: i: e: s:."), TestPlugins.drain(console));
    }

    @Test
    public void nothingFoundSaysSo() {
        commands.run(console, "hawkeye lookup u:nobody");
        assertEquals(List.of("No results found."), AwaitReplies.reply(server, console));
    }

    @Test
    public void pagesTurnWithThePageCommand() {
        LookupFixtures.breaks(Actors.NATURE, world, 20);

        commands.run(console, "hawkeye lookup");
        List<String> first = AwaitReplies.reply(server, console);
        assertEquals(10, first.size(), first.toString());
        assertEquals("Page 1/3  [next >]", first.getLast());

        commands.run(console, "hawkeye page 2");
        List<String> second = AwaitReplies.reply(server, console);
        assertEquals("Page 2/3 [< prev] [next >]", second.getLast());
        assertTrue(second.get(1).endsWith("(11/64/0/world)"), second.get(1));

        commands.run(console, "hawkeye page 3");
        List<String> third = AwaitReplies.reply(server, console);
        assertEquals(6, third.size(), third.toString());
        assertEquals("Page 3/3 [< prev] ", third.getLast());

        commands.run(console, "hawkeye page 9");
        assertEquals(List.of("There are only 3 page(s)."), AwaitReplies.reply(server, console));
    }

    @Test
    public void pagingWithoutALookupSaysSo() {
        commands.run(alex, "hawkeye page 2");
        assertEquals(List.of("You have no recent lookup to page through."), AwaitReplies.reply(server, alex));
    }

    @Test
    public void nearShowsWhatHappenedAroundThePlayer() {
        LookupFixtures.breaks(Actors.NATURE, world, 20);

        commands.run(alex, "hawkeye near");
        List<String> near = AwaitReplies.reply(server, alex);
        assertEquals(7, near.size(), near.toString());
        assertTrue(near.get(1).endsWith("(5/64/0/world)"), near.get(1));

        commands.run(alex, "hawkeye near 2");
        assertEquals(4, AwaitReplies.reply(server, alex).size());
    }

    @Test
    public void nearRefusesARadiusBeyondTheLimit() {
        commands.run(alex, "hawkeye near 1000");
        assertEquals(List.of("The largest radius you may use is 250."), TestPlugins.drain(alex));
    }

    @Test
    public void nearIsForPlayers() {
        commands.run(console, "hawkeye near");
        assertEquals(List.of("Only players can do that."), TestPlugins.drain(console));
    }

    @Test
    public void parameterSuggestionsCompleteTheLastToken() {
        List<String> suggestions = commands.suggestionFactory()
                .suggestImmediately(new TestCommandSource(alex), "hawkeye lookup t:1h u:Al").list().stream()
                .map(Suggestion::suggestion)
                .toList();
        assertTrue(suggestions.contains("t:1h u:Alex"), suggestions.toString());
    }

    @Test
    public void theSharedSuggestionsAreRegisteredBeforeAnyCommandUsesThem() {
        PaperCommand first = MossPaper.CONTEXT.getBeanProvider(PaperCommand.class).orderedStream().findFirst()
                .orElseThrow();
        assertInstanceOf(LookupCommand.class, first);
    }

    @Test
    public void containerTransactionsAreLookedUpByAction() {
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        recorder.record(Action.CONTAINER_DEPOSIT, Actors.of(alex), world, 1, 64, 1,
                "minecraft:diamond", null, 5, null);
        recorder.record(Action.VIRTUAL_DEPOSIT, Actors.of(alex), world, 9, 70, 9,
                "minecraft:emerald", null, 3, new Payload.VirtualItem("ender_chest", new byte[0]));
        LookupFixtures.breaks(Actors.NATURE, world, 2);

        commands.run(console, "hawkeye lookup a:+container");
        List<String> reply = AwaitReplies.reply(server, console);
        assertEquals(3, reply.size(), reply.toString());
        assertTrue(String.join("\n", reply).contains("Alex added 5x diamond"), reply.toString());
        assertTrue(String.join("\n", reply).contains("Alex added 3x emerald to ender_chest"), reply.toString());

        commands.run(console, "hawkeye lookup a:enderchest");
        List<String> virtual = AwaitReplies.reply(server, console);
        assertEquals(2, virtual.size(), virtual.toString());
        assertTrue(virtual.get(1).contains("Alex added 3x emerald to ender_chest"), virtual.get(1));
    }

    @Test
    public void withTheDialogSwitchOffAPlayerGetsChatLines() {
        DialogPlayer steve = new DialogPlayer(server, "Steve");
        server.addPlayer(steve);
        steve.setOp(true);
        TestPlugins.drain(steve);
        LookupFixtures.breaks(Actors.of(alex), world, 2);

        commands.run(steve, "hawkeye lookup");
        List<String> reply = AwaitReplies.reply(server, steve);

        assertEquals(3, reply.size(), reply.toString());
        assertEquals("----- Hawkeye results -----", reply.getFirst());
        assertEquals(0, steve.dialogCount());
    }

    @Test
    public void withTheDialogSwitchOnTheConsoleStillGetsChatLines() {
        TestPlugins.writeConfig(plugin, "logging:\n  sessions: false\n  join-snapshots: false\n"
                + "experimental:\n  dialog-lookup: true\n");
        TestPlugins.reload();
        LookupFixtures.breaks(Actors.of(alex), world, 2);

        commands.run(console, "hawkeye lookup");
        List<String> reply = AwaitReplies.reply(server, console);

        assertEquals(3, reply.size(), reply.toString());
        assertEquals("----- Hawkeye results -----", reply.getFirst());
    }

    private static List<String> clicks(Component component) {
        List<String> commands = new ArrayList<>();
        ClickEvent<?> click = component.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text text) {
            commands.add(text.value());
        }
        component.children().forEach(child -> commands.addAll(clicks(child)));
        return commands;
    }
}
