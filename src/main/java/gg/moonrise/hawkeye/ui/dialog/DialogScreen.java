package gg.moonrise.hawkeye.ui.dialog;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * What a dialog shows, free of Paper's dialog API so main can build it on any server: a title, lines of
 * text, input fields and buttons. {@link DialogScreens} turns it into a real dialog.
 *
 * @param title   the heading
 * @param body    the lines of text, top to bottom
 * @param width   how wide each line is drawn, 1 to 1024
 * @param fields  the inputs under the text, top to bottom
 * @param buttons the buttons at the bottom
 */
public record DialogScreen(@NotNull Component title, @NotNull List<Component> body, int width,
        @NotNull List<Field> fields, @NotNull Buttons buttons) {

    /** The widest body line the client accepts. */
    public static final int MAX_WIDTH = 1024;

    /** The input keys the client accepts. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * Validates and copies the lists.
     *
     * @throws IllegalArgumentException when the width is outside 1 to 1024
     */
    public DialogScreen {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(buttons, "buttons");
        body = List.copyOf(body);
        fields = List.copyOf(fields);
        if (width < 1 || width > MAX_WIDTH) {
            throw new IllegalArgumentException("width must be 1 to " + MAX_WIDTH + ": " + width);
        }
    }

    /** An input field; its {@link #key()} names its answer in {@link DialogAnswers}. */
    public sealed interface Field {

        /**
         * The name of this field's answer.
         *
         * @return letters, digits and underscores
         */
        @NotNull String key();

        /**
         * The text beside the field.
         *
         * @return the label
         */
        @NotNull Component label();

        /**
         * A one-line text field.
         *
         * @param key       the answer's name, letters, digits and underscores
         * @param label     the text beside it
         * @param initial   the text it starts with, cut to {@code maxLength}
         * @param maxLength the most characters it takes, at least 1
         */
        record Text(@NotNull String key, @NotNull Component label, @NotNull String initial, int maxLength)
                implements Field {

            /**
             * Validates the key and length, and cuts the initial text to fit.
             *
             * @throws IllegalArgumentException when the key is not letters, digits and underscores, or
             *                                  {@code maxLength} is below 1
             */
            public Text {
                checkKey(key);
                Objects.requireNonNull(label, "label");
                Objects.requireNonNull(initial, "initial");
                if (maxLength < 1) {
                    throw new IllegalArgumentException("maxLength must be at least 1: " + maxLength);
                }
                if (initial.length() > maxLength) {
                    initial = initial.substring(0, maxLength);
                }
            }
        }

        /**
         * A checkbox.
         *
         * @param key     the answer's name, letters, digits and underscores
         * @param label   the text beside it
         * @param initial whether it starts ticked
         */
        record Toggle(@NotNull String key, @NotNull Component label, boolean initial) implements Field {

            /**
             * Validates the key.
             *
             * @throws IllegalArgumentException when the key is not letters, digits and underscores
             */
            public Toggle {
                checkKey(key);
                Objects.requireNonNull(label, "label");
            }
        }
    }

    /**
     * A button that reports what the player entered.
     *
     * @param label   the button's text
     * @param handler gets the answers; it runs on the thread that handles the player's packet, so it hops to
     *                the player's thread itself before touching Bukkit state
     */
    public record Press(@NotNull Component label, @NotNull Consumer<DialogAnswers> handler) {

        /** Checks that neither part is null. */
        public Press {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /**
     * A button that only closes the dialog.
     *
     * @param label the button's text
     */
    public record Close(@NotNull Component label) {

        /** Checks that the label is not null. */
        public Close {
            Objects.requireNonNull(label, "label");
        }
    }

    /** The buttons at the bottom of a dialog. */
    public sealed interface Buttons {

        /**
         * Several buttons in a grid, then one that closes (Paper's multi-action dialog).
         *
         * @param actions the buttons, in order; at least one
         * @param exit    the closing button under them
         */
        record Listed(@NotNull List<Press> actions, @NotNull Close exit) implements Buttons {

            /**
             * Copies the buttons.
             *
             * @throws IllegalArgumentException when there are none
             */
            public Listed {
                actions = List.copyOf(actions);
                Objects.requireNonNull(exit, "exit");
                if (actions.isEmpty()) {
                    throw new IllegalArgumentException("a listed dialog needs at least one action");
                }
            }
        }

        /**
         * A yes and a no button (Paper's confirmation dialog).
         *
         * @param confirm the button that reports the answers
         * @param cancel  the button that closes
         */
        record Choice(@NotNull Press confirm, @NotNull Close cancel) implements Buttons {

            /** Checks that neither button is null. */
            public Choice {
                Objects.requireNonNull(confirm, "confirm");
                Objects.requireNonNull(cancel, "cancel");
            }
        }
    }

    private static void checkKey(@NotNull String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("a field key is letters, digits and underscores: " + key);
        }
    }
}
