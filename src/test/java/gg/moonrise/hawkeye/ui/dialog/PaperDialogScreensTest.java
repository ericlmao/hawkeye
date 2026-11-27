package gg.moonrise.hawkeye.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.dialog.DialogPlayer;
import gg.moonrise.hawkeye.support.dialog.ShownDialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class PaperDialogScreensTest {

    private static final Component TITLE = Component.text("Lookup", NamedTextColor.GOLD);
    private static final List<Component> BODY = List.of(Component.text("1. Alex broke stone"),
            Component.text("2. Steve placed dirt", NamedTextColor.GRAY));
    private static final List<DialogScreen.Field> FIELDS = List.of(
            new DialogScreen.Field.Text("user", Component.text("User"), "Alex", 16),
            new DialogScreen.Field.Text("time", Component.text("Time"), "1h", 8),
            new DialogScreen.Field.Toggle("count", Component.text("Count only"), true),
            new DialogScreen.Field.Toggle("network", Component.text("Network"), false));

    private final PaperDialogScreens screens = new PaperDialogScreens();
    private ServerMock server;
    private DialogPlayer alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        alex = new DialogPlayer(server, "Alex");
        server.addPlayer(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theServerSupportsDialogs() {
        assertTrue(screens.supported());
    }

    @Test
    public void theTitleBodyAndBaseAreMapped() {
        ShownDialog shown = show(listed(300, press("Search", answers -> {
        })));

        assertEquals(TITLE, shown.title());
        assertEquals(BODY, shown.body());
        assertEquals(List.of("1. Alex broke stone", "2. Steve placed dirt"), shown.plainBody());
        assertEquals(List.of(300, 300), shown.bodyWidths());
        assertEquals(DialogBase.DialogAfterAction.CLOSE, shown.afterAction());
        assertFalse(shown.pause());
        assertTrue(shown.canCloseWithEscape());
        assertNull(shown.base().externalTitle());
    }

    @Test
    public void theFieldsAreMappedInOrder() {
        ShownDialog shown = show(listed(250, press("Search", answers -> {
        })));

        assertEquals(List.of("user", "time", "count", "network"), List.copyOf(shown.inputLabels().keySet()));
        assertEquals(Map.of("user", "User", "time", "Time", "count", "Count only", "network", "Network"),
                shown.inputLabels());
        assertEquals(Map.of("user", "Alex", "time", "1h"), shown.textInitials());
        assertEquals(Map.of("user", 16, "time", 8), shown.maxLengths());
        assertEquals(Map.of("user", 250, "time", 250), shown.textWidths());
        assertEquals(Map.of("count", true, "network", false), shown.toggleInitials());
    }

    @Test
    public void listedButtonsKeepTheirOrderAndSpreadOverAtMostThreeColumns() {
        DialogScreen.Press first = press("First", answers -> {
        });
        DialogScreen.Press second = press("Second", answers -> {
        });
        DialogScreen.Press third = press("Third", answers -> {
        });
        DialogScreen.Press fourth = press("Fourth", answers -> {
        });

        ShownDialog one = show(listed(200, first));
        assertFalse(one.isChoice());
        assertEquals(List.of("First"), one.buttons());
        assertEquals("Close", one.exit());
        assertEquals(1, one.columns());
        assertNull(one.exitButton().action(), "the exit button only closes");

        assertEquals(2, show(listed(200, first, second)).columns());
        assertEquals(3, show(listed(200, first, second, third)).columns());
        ShownDialog four = show(listed(200, first, second, third, fourth));
        assertEquals(List.of("First", "Second", "Third", "Fourth"), four.buttons());
        assertEquals(3, four.columns());
        assertEquals(100, four.button("Fourth").width());
        assertEquals(100, four.exitButton().width());
    }

    @Test
    public void aChoiceIsAConfirmation() {
        DialogScreen screen = new DialogScreen(TITLE, BODY, 200, FIELDS, new DialogScreen.Buttons.Choice(
                press("Apply", answers -> {
                }), new DialogScreen.Close(Component.text("Cancel"))));

        ShownDialog shown = show(screen);

        assertTrue(shown.isChoice());
        assertEquals(List.of("Apply"), shown.buttons());
        assertEquals("Cancel", shown.exit());
        assertNull(shown.exitButton().action());
        assertEquals(100, shown.button("Apply").width());
    }

    @Test
    public void aButtonsCallbackWorksOnceForAnHour() {
        ShownDialog shown = show(listed(200, press("Search", answers -> {
        })));

        assertEquals(1, shown.options("Search").uses());
        assertEquals(Duration.ofHours(1), shown.options("Search").lifetime());
    }

    @Test
    public void pressingAButtonPassesWhatWasEntered() {
        AtomicReference<DialogAnswers> received = new AtomicReference<>();
        ShownDialog shown = show(listed(200, press("Other", answers -> {
        }), press("Search", received::set)));

        shown.press("Search", Map.of("user", "Steve", "time", "2d"), Map.of("count", false, "network", true));

        assertEquals(new DialogAnswers(Map.of("user", "Steve", "time", "2d"),
                Map.of("count", false, "network", true)), received.get());
    }

    @Test
    public void whatTheClientLeftOutArrivesEmptyAndUnticked() {
        AtomicReference<DialogAnswers> received = new AtomicReference<>();
        ShownDialog shown = show(listed(200, press("Search", received::set)));

        shown.press("Search", Map.of("user", "Steve"), Map.of());

        DialogAnswers answers = received.get();
        assertEquals("Steve", answers.text("user"));
        assertEquals("", answers.text("time"));
        assertFalse(answers.toggle("count"));
        assertFalse(answers.toggle("network"));
        assertEquals(Map.of("user", "Steve", "time", ""), answers.texts());
        assertEquals(Map.of("count", false, "network", false), answers.toggles());
    }

    @Test
    public void aFailingHandlerDoesNotReachTheCaller() {
        ShownDialog shown = show(listed(200, press("Search", answers -> {
            throw new IllegalStateException("handler failed");
        })));

        assertDoesNotThrow(() -> shown.press("Search", Map.of(), Map.of()));
    }

    @Test
    public void aDialogThatCannotBeShownReportsFalse() {
        FailingPlayer steve = new FailingPlayer(server, "Steve");
        server.addPlayer(steve);

        assertFalse(screens.show(steve, listed(200, press("Search", answers -> {
        }))));
        assertEquals(0, steve.dialogCount());
    }

    @Test
    public void aShownDialogReportsTrueAndReachesThePlayerOnce() {
        assertTrue(alex.lastDialog().isEmpty());

        assertTrue(screens.show(alex, listed(200, press("Search", answers -> {
        }))));

        assertEquals(1, alex.dialogCount());
        assertTrue(alex.lastDialog().isPresent());
    }

    @Test
    public void theBodyKeepsTheScreensComponents() {
        ShownDialog shown = show(listed(200, press("Search", answers -> {
        })));

        assertSame(BODY.get(1), shown.body().get(1));
    }

    private @NotNull ShownDialog show(@NotNull DialogScreen screen) {
        int before = alex.dialogCount();
        assertTrue(screens.show(alex, screen));
        assertEquals(before + 1, alex.dialogCount());
        return alex.lastDialog().orElseThrow();
    }

    private static @NotNull DialogScreen listed(int width, @NotNull DialogScreen.Press @NotNull ... actions) {
        return new DialogScreen(TITLE, BODY, width, FIELDS, new DialogScreen.Buttons.Listed(List.of(actions),
                new DialogScreen.Close(Component.text("Close"))));
    }

    private static @NotNull DialogScreen.Press press(@NotNull String label,
            @NotNull Consumer<DialogAnswers> handler) {
        return new DialogScreen.Press(Component.text(label), handler);
    }

    /** A player whose client cannot be sent a dialog. */
    private static final class FailingPlayer extends DialogPlayer {

        private FailingPlayer(@NotNull ServerMock server, @NotNull String name) {
            super(server, name);
        }

        @Override
        public void showDialog(@NotNull DialogLike dialog) {
            throw new IllegalStateException("the connection is gone");
        }
    }
}
