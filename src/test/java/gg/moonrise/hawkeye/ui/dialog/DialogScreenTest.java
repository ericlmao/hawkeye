package gg.moonrise.hawkeye.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class DialogScreenTest {

    private static final DialogScreen.Close CLOSE = new DialogScreen.Close(Component.text("Close"));
    private static final DialogScreen.Press PRESS = new DialogScreen.Press(Component.text("Search"), answers -> {
    });

    @Test
    public void aTextFieldCutsItsInitialTextToItsMaxLength() {
        DialogScreen.Field.Text text = new DialogScreen.Field.Text("user", Component.text("User"), "abcdefgh", 5);
        assertEquals("abcde", text.initial());
        assertEquals("abc", new DialogScreen.Field.Text("user", Component.text("User"), "abc", 5).initial());
        assertEquals("abcde", new DialogScreen.Field.Text("user", Component.text("User"), "abcde", 5).initial());
    }

    @Test
    public void aTextFieldTakesAtLeastOneCharacter() {
        assertThrows(IllegalArgumentException.class,
                () -> new DialogScreen.Field.Text("user", Component.text("User"), "", 0));
        assertEquals(1, new DialogScreen.Field.Text("user", Component.text("User"), "", 1).maxLength());
    }

    @Test
    public void fieldKeysAreLettersDigitsAndUnderscores() {
        assertEquals("Max_radius2", new DialogScreen.Field.Toggle("Max_radius2", Component.text("R"), true).key());
        for (String key : List.of("", "a b", "a-b", "a:b", "é")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DialogScreen.Field.Text(key, Component.text("L"), "", 10), key);
            assertThrows(IllegalArgumentException.class,
                    () -> new DialogScreen.Field.Toggle(key, Component.text("L"), false), key);
        }
    }

    @Test
    public void theWidthIsOneTo1024() {
        assertEquals(1, screen(1).width());
        assertEquals(1024, screen(1024).width());
        assertThrows(IllegalArgumentException.class, () -> screen(0));
        assertThrows(IllegalArgumentException.class, () -> screen(1025));
    }

    @Test
    public void aListedDialogNeedsAnAction() {
        assertThrows(IllegalArgumentException.class, () -> new DialogScreen.Buttons.Listed(List.of(), CLOSE));
    }

    @Test
    public void theListsAreCopied() {
        List<Component> body = new ArrayList<>(List.of(Component.text("one")));
        List<DialogScreen.Field> fields = new ArrayList<>(
                List.of(new DialogScreen.Field.Toggle("a", Component.text("A"), false)));
        List<DialogScreen.Press> actions = new ArrayList<>(List.of(PRESS));
        DialogScreen.Buttons.Listed listed = new DialogScreen.Buttons.Listed(actions, CLOSE);
        DialogScreen screen = new DialogScreen(Component.text("Title"), body, 200, fields, listed);

        body.add(Component.text("two"));
        fields.clear();
        actions.add(PRESS);

        assertEquals(List.of(Component.text("one")), screen.body());
        assertEquals(1, screen.fields().size());
        assertEquals(1, listed.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> screen.body().add(Component.text("three")));
    }

    @Test
    public void theAnswersAreCopiedAndDefaultToEmptyAndUnticked() {
        Map<String, String> texts = new HashMap<>(Map.of("user", "Alex"));
        Map<String, Boolean> toggles = new HashMap<>(Map.of("count", true));
        DialogAnswers answers = new DialogAnswers(texts, toggles);
        texts.put("user", "Steve");
        toggles.put("count", false);

        assertEquals("Alex", answers.text("user"));
        assertTrue(answers.toggle("count"));
        assertEquals("", answers.text("time"));
        assertFalse(answers.toggle("preview"));
    }

    private static @NotNull DialogScreen screen(int width) {
        return new DialogScreen(Component.text("Title"), List.of(), width, List.of(),
                new DialogScreen.Buttons.Choice(PRESS, CLOSE));
    }
}
