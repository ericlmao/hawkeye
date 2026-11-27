package gg.moonrise.hawkeye.support.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * A dialog a {@link DialogPlayer} was shown, read back from {@link FakeDialogProvider}'s records. Inputs are
 * listed in their order on screen; buttons are named by their plain label.
 */
public final class ShownDialog {

    private final @NotNull FakeDialogParts.FakeDialog dialog;
    private final @NotNull Audience viewer;

    private ShownDialog(@NotNull FakeDialogParts.FakeDialog dialog, @NotNull Audience viewer) {
        this.dialog = dialog;
        this.viewer = viewer;
    }

    /**
     * Reads a dialog.
     *
     * @param dialog what was shown; must come from {@link FakeDialogProvider}
     * @param viewer who was shown it, the audience a press reports
     * @return the reader
     */
    public static @NotNull ShownDialog of(@NotNull DialogLike dialog, @NotNull Audience viewer) {
        if (!(dialog instanceof FakeDialogParts.FakeDialog fake)) {
            throw new AssertionError("not a dialog from the fake provider: " + dialog);
        }
        return new ShownDialog(fake, viewer);
    }

    /**
     * The dialog's base.
     *
     * @return the base
     */
    public @NotNull DialogBase base() {
        return dialog.base();
    }

    /**
     * The dialog's type.
     *
     * @return a multi-action or confirmation type, for what {@code PaperDialogScreens} builds
     */
    public @NotNull DialogType type() {
        return dialog.type();
    }

    /**
     * The heading.
     *
     * @return the title
     */
    public @NotNull Component title() {
        return base().title();
    }

    /**
     * The body lines.
     *
     * @return each plain-message body's contents, top to bottom
     */
    public @NotNull List<Component> body() {
        return plainMessages().stream().map(FakeDialogParts.FakePlainMessage::contents).toList();
    }

    /**
     * The body lines as plain text.
     *
     * @return each body line without formatting
     */
    public @NotNull List<String> plainBody() {
        return body().stream().map(ShownDialog::plain).toList();
    }

    /**
     * The width of each body line.
     *
     * @return the widths, top to bottom
     */
    public @NotNull List<Integer> bodyWidths() {
        return plainMessages().stream().map(FakeDialogParts.FakePlainMessage::width).toList();
    }

    /**
     * The inputs, in order.
     *
     * @return the inputs
     */
    public @NotNull List<DialogInput> inputs() {
        return base().inputs();
    }

    /**
     * The text fields' starting text.
     *
     * @return key to initial text, in order
     */
    public @NotNull Map<String, String> textInitials() {
        Map<String, String> initials = new LinkedHashMap<>();
        texts().forEach(text -> initials.put(text.key(), text.initial()));
        return initials;
    }

    /**
     * The text fields' longest input.
     *
     * @return key to max length, in order
     */
    public @NotNull Map<String, Integer> maxLengths() {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        texts().forEach(text -> lengths.put(text.key(), text.maxLength()));
        return lengths;
    }

    /**
     * The text fields' widths.
     *
     * @return key to width, in order
     */
    public @NotNull Map<String, Integer> textWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        texts().forEach(text -> widths.put(text.key(), text.width()));
        return widths;
    }

    /**
     * The checkboxes' starting state.
     *
     * @return key to initial state, in order
     */
    public @NotNull Map<String, Boolean> toggleInitials() {
        Map<String, Boolean> initials = new LinkedHashMap<>();
        for (DialogInput input : inputs()) {
            if (input instanceof FakeDialogParts.FakeBooleanInput toggle) {
                initials.put(toggle.key(), toggle.initial());
            }
        }
        return initials;
    }

    /**
     * Every input's label as plain text.
     *
     * @return key to label, in order
     */
    public @NotNull Map<String, String> inputLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (DialogInput input : inputs()) {
            switch (input) {
                case FakeDialogParts.FakeTextInput text -> labels.put(text.key(), plain(text.label()));
                case FakeDialogParts.FakeBooleanInput toggle -> labels.put(toggle.key(), plain(toggle.label()));
                default -> throw new AssertionError("an input the fake provider does not build: " + input);
            }
        }
        return labels;
    }

    /**
     * The buttons that are not the exit: a multi-action dialog's actions, or a confirmation's yes button.
     *
     * @return their plain labels, in order
     */
    public @NotNull List<String> buttons() {
        return actionButtons().stream().map(button -> plain(button.label())).toList();
    }

    /**
     * The exit button: a multi-action dialog's exit, or a confirmation's no button.
     *
     * @return its plain label
     */
    public @NotNull String exit() {
        return plain(exitButton().label());
    }

    /**
     * A button by its plain label, the exit included.
     *
     * @param label the button's plain label
     * @return the button
     */
    public @NotNull ActionButton button(@NotNull String label) {
        return find(label).orElseThrow(() -> new AssertionError("no button \"" + label + "\" among "
                + buttons() + " and exit \"" + exit() + "\""));
    }

    /**
     * The exit button itself.
     *
     * @return the button
     */
    public @NotNull ActionButton exitButton() {
        return switch (type()) {
            case FakeDialogParts.FakeMultiAction multi -> {
                if (multi.exitAction() == null) {
                    throw new AssertionError("the multi-action dialog has no exit button");
                }
                yield multi.exitAction();
            }
            case FakeDialogParts.FakeConfirmation choice -> choice.noButton();
            default -> throw new AssertionError("a dialog type the fake provider does not build: " + type());
        };
    }

    /**
     * What happens after a button is pressed.
     *
     * @return the after action
     */
    public DialogBase.@NotNull DialogAfterAction afterAction() {
        return base().afterAction();
    }

    /**
     * Whether the dialog pauses a single-player game.
     *
     * @return the pause flag
     */
    public boolean pause() {
        return base().pause();
    }

    /**
     * Whether escape closes the dialog.
     *
     * @return the escape flag
     */
    public boolean canCloseWithEscape() {
        return base().canCloseWithEscape();
    }

    /**
     * How many columns a multi-action dialog spreads its buttons over.
     *
     * @return the column count
     */
    public int columns() {
        if (!(type() instanceof FakeDialogParts.FakeMultiAction multi)) {
            throw new AssertionError("not a multi-action dialog: " + type());
        }
        return multi.columns();
    }

    /**
     * Whether this is a confirmation dialog (a yes and a no button).
     *
     * @return true for a confirmation
     */
    public boolean isChoice() {
        return type() instanceof FakeDialogParts.FakeConfirmation;
    }

    /**
     * The callback options of a button.
     *
     * @param label the button's plain label
     * @return its options
     */
    public @NotNull ClickCallback.Options options(@NotNull String label) {
        return callback(label).options();
    }

    /**
     * Presses a button the way a client does, reporting the given answers and the player as audience.
     *
     * @param label   the button's plain label
     * @param texts   what the text fields hold; a missing key reports nothing for that field
     * @param toggles what the checkboxes hold; a missing key reports nothing for that field
     */
    public void press(@NotNull String label, @NotNull Map<String, String> texts,
            @NotNull Map<String, Boolean> toggles) {
        callback(label).callback().accept(new FakeDialogResponse(texts, toggles), viewer);
    }

    private @NotNull FakeDialogParts.FakeCallbackAction callback(@NotNull String label) {
        ActionButton button = button(label);
        if (!(button.action() instanceof FakeDialogParts.FakeCallbackAction action)) {
            throw new AssertionError("button \"" + label + "\" has no callback: " + button.action());
        }
        return action;
    }

    private @NotNull Optional<ActionButton> find(@NotNull String label) {
        for (ActionButton button : actionButtons()) {
            if (plain(button.label()).equals(label)) {
                return Optional.of(button);
            }
        }
        ActionButton exit = exitButton();
        return plain(exit.label()).equals(label) ? Optional.of(exit) : Optional.empty();
    }

    private @NotNull List<ActionButton> actionButtons() {
        return switch (type()) {
            case FakeDialogParts.FakeMultiAction multi -> multi.actions();
            case FakeDialogParts.FakeConfirmation choice -> List.of(choice.yesButton());
            default -> throw new AssertionError("a dialog type the fake provider does not build: " + type());
        };
    }

    private @NotNull List<FakeDialogParts.FakePlainMessage> plainMessages() {
        return base().body().stream().map(ShownDialog::plainMessage).toList();
    }

    private @NotNull List<FakeDialogParts.FakeTextInput> texts() {
        return inputs().stream()
                .filter(FakeDialogParts.FakeTextInput.class::isInstance)
                .map(FakeDialogParts.FakeTextInput.class::cast)
                .toList();
    }

    private static @NotNull FakeDialogParts.FakePlainMessage plainMessage(@NotNull DialogBody body) {
        if (!(body instanceof FakeDialogParts.FakePlainMessage message)) {
            throw new AssertionError("a body the fake provider does not build: " + body);
        }
        return message;
    }

    private static @NotNull String plain(@NotNull Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
