package gg.moonrise.hawkeye.core.service.capture.text;

import gg.moonrise.hawkeye.util.text.TextCuts;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Text recorded from components (kick reasons, book pages) in the one-line form a lookup result
 * shows: plain text, line breaks folded into spaces, optionally cut to a length.
 */
public final class PlainText {

    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");

    private PlainText() {
    }

    /**
     * The plain text of a component on one line.
     *
     * @param component the component
     * @return its text, each line break (with the spaces around it) replaced by one space, trimmed
     */
    public static @NotNull String oneLine(@NotNull Component component) {
        return oneLine(PlainTextComponentSerializer.plainText().serialize(component));
    }

    /**
     * A text on one line.
     *
     * @param text the text
     * @return the text, each line break (with the spaces around it) replaced by one space, trimmed
     */
    public static @NotNull String oneLine(@NotNull String text) {
        return LINE_BREAKS.matcher(text).replaceAll(" ").strip();
    }

    /**
     * A text cut to a length, never between the two halves of a surrogate pair ({@link TextCuts#head}).
     *
     * @param text the text
     * @param max  the most characters to keep
     * @return the text, or its first {@code max} characters (one fewer when that would split a pair)
     */
    public static @NotNull String capped(@NotNull String text, int max) {
        return TextCuts.head(text, max);
    }
}
