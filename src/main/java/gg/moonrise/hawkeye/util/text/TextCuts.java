package gg.moonrise.hawkeye.util.text;

import org.jetbrains.annotations.NotNull;

/**
 * Cuts text to a length without splitting a surrogate pair: a lone high surrogate prints as {@code ?} in the
 * console and in Discord.
 */
public final class TextCuts {

    private TextCuts() {
    }

    /**
     * The start of a text.
     *
     * @param text the text
     * @param max  the most characters to keep, not negative
     * @return the text when it is no longer, else its first {@code max} characters (one fewer when the last would be
     *         the first half of a surrogate pair)
     */
    public static @NotNull String head(@NotNull String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max > 0 && Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}
