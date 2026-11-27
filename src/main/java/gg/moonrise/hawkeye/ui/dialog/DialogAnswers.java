package gg.moonrise.hawkeye.ui.dialog;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * What a player entered in a dialog's fields when pressing a button, by field key.
 *
 * @param texts   the text fields' contents
 * @param toggles the checkboxes' states
 */
public record DialogAnswers(@NotNull Map<String, String> texts, @NotNull Map<String, Boolean> toggles) {

    /** Copies the maps. */
    public DialogAnswers {
        texts = Map.copyOf(texts);
        toggles = Map.copyOf(toggles);
    }

    /**
     * A text field's contents.
     *
     * @param key the field's key
     * @return what was typed, or {@code ""} when the dialog reported nothing for it
     */
    public @NotNull String text(@NotNull String key) {
        return texts.getOrDefault(key, "");
    }

    /**
     * A checkbox's state.
     *
     * @param key the field's key
     * @return whether it was ticked; false when the dialog reported nothing for it
     */
    public boolean toggle(@NotNull String key) {
        return toggles.getOrDefault(key, false);
    }
}
