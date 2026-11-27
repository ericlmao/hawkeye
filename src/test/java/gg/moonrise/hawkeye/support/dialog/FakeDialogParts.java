package gg.moonrise.hawkeye.support.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.ConfirmationType;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.set.RegistryValueSetBuilder;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The values and builders {@link FakeDialogProvider} hands out, standing in for the server's: plain records
 * a test reads back through {@link ShownDialog}. The builders start from defaults (pause on, two columns,
 * buttons 150 wide) that differ from what {@code PaperDialogScreens} sets, so a test sees whether it set them.
 */
public final class FakeDialogParts {

    private FakeDialogParts() {
    }

    /**
     * A dialog built by {@code Dialog.create}.
     *
     * @param base its base
     * @param type its type
     */
    public record FakeDialog(@NotNull DialogBase base, @NotNull DialogType type) implements Dialog {

        /** Paper 26.3 deprecates {@code Keyed#getKey} for removal, but {@link Dialog} still declares it abstract. */
        @Override
        @SuppressWarnings("removal")
        public @NotNull NamespacedKey getKey() {
            return new NamespacedKey("test", "dialog");
        }
    }

    /**
     * A button action that calls back into the plugin.
     *
     * @param id        the custom click id
     * @param additions the extra payload (none for a callback)
     * @param callback  what the press runs
     * @param options   how often and how long the callback works
     */
    public record FakeCallbackAction(@NotNull Key id, @Nullable BinaryTagHolder additions,
            @NotNull DialogActionCallback callback, @NotNull ClickCallback.Options options)
            implements DialogAction.CustomClickAction {
    }

    /**
     * A dialog's base.
     *
     * @param title              the heading
     * @param externalTitle      the title on buttons that open it
     * @param canCloseWithEscape whether escape closes it
     * @param pause              whether it pauses a single-player game
     * @param afterAction        what happens after a button
     * @param body               the bodies
     * @param inputs             the inputs
     */
    public record FakeDialogBase(@NotNull Component title, @Nullable Component externalTitle, boolean canCloseWithEscape,
            boolean pause, @NotNull DialogAfterAction afterAction, @NotNull List<DialogBody> body,
            @NotNull List<DialogInput> inputs) implements DialogBase {

        /** Copies the lists. */
        public FakeDialogBase {
            body = List.copyOf(body);
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * A button.
     *
     * @param label   its text
     * @param tooltip its tooltip
     * @param width   its width
     * @param action  what it does; null only closes
     */
    public record FakeActionButton(@NotNull Component label, @Nullable Component tooltip, int width,
            @Nullable DialogAction action) implements ActionButton {
    }

    /**
     * A line of text.
     *
     * @param contents the text
     * @param width    how wide it is drawn
     */
    public record FakePlainMessage(@NotNull Component contents, int width) implements PlainMessageDialogBody {
    }

    /**
     * A checkbox.
     *
     * @param key     its answer's name
     * @param label   its label
     * @param initial whether it starts ticked
     * @param onTrue  the template value when ticked
     * @param onFalse the template value when not
     */
    public record FakeBooleanInput(@NotNull String key, @NotNull Component label, boolean initial,
            @NotNull String onTrue, @NotNull String onFalse) implements BooleanDialogInput {
    }

    /**
     * A text field.
     *
     * @param key          its answer's name
     * @param width        its width
     * @param label        its label
     * @param labelVisible whether the label shows
     * @param initial      its starting text
     * @param maxLength    the most characters it takes
     * @param multiline    its multi-line options, null for one line
     */
    public record FakeTextInput(@NotNull String key, int width, @NotNull Component label, boolean labelVisible,
            @NotNull String initial, int maxLength, @Nullable MultilineOptions multiline) implements TextDialogInput {
    }

    /**
     * A grid of buttons with an exit button.
     *
     * @param actions    the buttons
     * @param exitAction the exit button
     * @param columns    how many columns
     */
    public record FakeMultiAction(@NotNull List<ActionButton> actions, @Nullable ActionButton exitAction, int columns)
            implements MultiActionType {

        /** Copies the buttons. */
        public FakeMultiAction {
            actions = List.copyOf(actions);
        }
    }

    /**
     * A yes and a no button.
     *
     * @param yesButton the yes button
     * @param noButton  the no button
     */
    public record FakeConfirmation(@NotNull ActionButton yesButton, @NotNull ActionButton noButton)
            implements ConfirmationType {
    }

    /** The factory {@code Dialog.create} hands its consumer. */
    public static final class DialogFactory implements RegistryBuilderFactory<Dialog, DialogBuilder> {

        private @Nullable DialogBuilder builder;

        @Override
        public @NotNull DialogBuilder empty() {
            builder = new DialogBuilder();
            return builder;
        }

        @Override
        public @NotNull DialogBuilder copyFrom(@NotNull TypedKey<Dialog> key) {
            throw new UnsupportedOperationException("the fake dialog factory copies nothing");
        }

        /**
         * The dialog the consumer built.
         *
         * @return the dialog
         */
        public @NotNull FakeDialog dialog() {
            DialogBuilder built = Objects.requireNonNull(builder, "the consumer never called empty()");
            return new FakeDialog(Objects.requireNonNull(built.base, "no base"),
                    Objects.requireNonNull(built.type, "no type"));
        }
    }

    /** The dialog registry entry builder. */
    public static final class DialogBuilder implements DialogRegistryEntry.Builder {

        private @Nullable DialogBase base;
        private @Nullable DialogType type;

        @Override
        public @Nullable DialogBase base() {
            return base;
        }

        @Override
        public @Nullable DialogType type() {
            return type;
        }

        @Override
        public @NotNull RegistryValueSetBuilder<Dialog, DialogRegistryEntry.Builder> registryValueSet() {
            throw new UnsupportedOperationException("the fake dialog builder has no registry value sets");
        }

        @Override
        public @NotNull DialogRegistryEntry.Builder base(@NotNull DialogBase base) {
            this.base = base;
            return this;
        }

        @Override
        public @NotNull DialogRegistryEntry.Builder type(@NotNull DialogType type) {
            this.type = type;
            return this;
        }
    }

    /** The base builder. */
    public static final class BaseBuilder implements DialogBase.Builder {

        private final @NotNull Component title;
        private @Nullable Component externalTitle;
        private boolean canCloseWithEscape = true;
        private boolean pause = true;
        private DialogBase.@NotNull DialogAfterAction afterAction = DialogBase.DialogAfterAction.CLOSE;
        private @NotNull List<DialogBody> body = List.of();
        private @NotNull List<DialogInput> inputs = List.of();

        /**
         * Starts a base.
         *
         * @param title the heading
         */
        public BaseBuilder(@NotNull Component title) {
            this.title = title;
        }

        @Override
        public @NotNull DialogBase.Builder externalTitle(@Nullable Component externalTitle) {
            this.externalTitle = externalTitle;
            return this;
        }

        @Override
        public @NotNull DialogBase.Builder canCloseWithEscape(boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return this;
        }

        @Override
        public @NotNull DialogBase.Builder pause(boolean pause) {
            this.pause = pause;
            return this;
        }

        @Override
        public @NotNull DialogBase.Builder afterAction(DialogBase.@NotNull DialogAfterAction afterAction) {
            this.afterAction = afterAction;
            return this;
        }

        @Override
        public @NotNull DialogBase.Builder body(@NotNull List<? extends DialogBody> body) {
            this.body = List.copyOf(body);
            return this;
        }

        @Override
        public @NotNull DialogBase.Builder inputs(@NotNull List<? extends DialogInput> inputs) {
            this.inputs = List.copyOf(inputs);
            return this;
        }

        @Override
        public @NotNull DialogBase build() {
            return new FakeDialogBase(title, externalTitle, canCloseWithEscape, pause, afterAction, body, inputs);
        }
    }

    /** The button builder. */
    public static final class ButtonBuilder implements ActionButton.Builder {

        private final @NotNull Component label;
        private @Nullable Component tooltip;
        private int width = 150;
        private @Nullable DialogAction action;

        /**
         * Starts a button.
         *
         * @param label its text
         */
        public ButtonBuilder(@NotNull Component label) {
            this.label = label;
        }

        @Override
        public @NotNull ActionButton.Builder tooltip(@Nullable Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        @Override
        public @NotNull ActionButton.Builder width(int width) {
            this.width = width;
            return this;
        }

        @Override
        public @NotNull ActionButton.Builder action(@Nullable DialogAction action) {
            this.action = action;
            return this;
        }

        @Override
        public @NotNull ActionButton build() {
            return new FakeActionButton(label, tooltip, width, action);
        }
    }

    /** The checkbox builder. */
    public static final class BooleanBuilder implements BooleanDialogInput.Builder {

        private final @NotNull String key;
        private final @NotNull Component label;
        private boolean initial;
        private @NotNull String onTrue = "true";
        private @NotNull String onFalse = "false";

        /**
         * Starts a checkbox.
         *
         * @param key   its answer's name
         * @param label its label
         */
        public BooleanBuilder(@NotNull String key, @NotNull Component label) {
            this.key = key;
            this.label = label;
        }

        @Override
        public @NotNull BooleanDialogInput.Builder initial(boolean initial) {
            this.initial = initial;
            return this;
        }

        @Override
        public @NotNull BooleanDialogInput.Builder onTrue(@NotNull String onTrue) {
            this.onTrue = onTrue;
            return this;
        }

        @Override
        public @NotNull BooleanDialogInput.Builder onFalse(@NotNull String onFalse) {
            this.onFalse = onFalse;
            return this;
        }

        @Override
        public @NotNull BooleanDialogInput build() {
            return new FakeBooleanInput(key, label, initial, onTrue, onFalse);
        }
    }

    /** The text field builder. */
    public static final class TextBuilder implements TextDialogInput.Builder {

        private final @NotNull String key;
        private final @NotNull Component label;
        private int width = 200;
        private boolean labelVisible = true;
        private @NotNull String initial = "";
        private int maxLength = 32;
        private TextDialogInput.@Nullable MultilineOptions multiline;

        /**
         * Starts a text field.
         *
         * @param key   its answer's name
         * @param label its label
         */
        public TextBuilder(@NotNull String key, @NotNull Component label) {
            this.key = key;
            this.label = label;
        }

        @Override
        public @NotNull TextDialogInput.Builder width(int width) {
            this.width = width;
            return this;
        }

        @Override
        public @NotNull TextDialogInput.Builder labelVisible(boolean labelVisible) {
            this.labelVisible = labelVisible;
            return this;
        }

        @Override
        public @NotNull TextDialogInput.Builder initial(@NotNull String initial) {
            this.initial = initial;
            return this;
        }

        @Override
        public @NotNull TextDialogInput.Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        @Override
        public @NotNull TextDialogInput.Builder multiline(TextDialogInput.@Nullable MultilineOptions multiline) {
            this.multiline = multiline;
            return this;
        }

        @Override
        public @NotNull TextDialogInput build() {
            return new FakeTextInput(key, width, label, labelVisible, initial, maxLength, multiline);
        }
    }

    /** The multi-action type builder. */
    public static final class MultiActionBuilder implements MultiActionType.Builder {

        private final @NotNull List<ActionButton> actions;
        private @Nullable ActionButton exitAction;
        private int columns = 2;

        /**
         * Starts a grid of buttons.
         *
         * @param actions the buttons
         */
        public MultiActionBuilder(@NotNull List<ActionButton> actions) {
            this.actions = List.copyOf(actions);
        }

        @Override
        public @NotNull MultiActionType.Builder exitAction(@Nullable ActionButton exitAction) {
            this.exitAction = exitAction;
            return this;
        }

        @Override
        public @NotNull MultiActionType.Builder columns(int columns) {
            this.columns = columns;
            return this;
        }

        @Override
        public @NotNull MultiActionType build() {
            return new FakeMultiAction(actions, exitAction, columns);
        }
    }
}
