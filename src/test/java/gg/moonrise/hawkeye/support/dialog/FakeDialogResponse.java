package gg.moonrise.hawkeye.support.dialog;

import io.papermc.paper.dialog.DialogResponseView;
import java.util.Map;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a client sends back when a dialog button is pressed: an absent key reads as null, as on Paper.
 *
 * @param texts   the text fields' contents by key
 * @param toggles the checkboxes' states by key
 */
public record FakeDialogResponse(@NotNull Map<String, String> texts, @NotNull Map<String, Boolean> toggles)
        implements DialogResponseView {

    /** Copies the maps. */
    public FakeDialogResponse {
        texts = Map.copyOf(texts);
        toggles = Map.copyOf(toggles);
    }

    @Override
    public @NotNull BinaryTagHolder payload() {
        throw new UnsupportedOperationException("the fake dialog response has no payload");
    }

    @Override
    public @Nullable String getText(@NotNull String key) {
        return texts.get(key);
    }

    @Override
    public @Nullable Boolean getBoolean(@NotNull String key) {
        return toggles.get(key);
    }

    @Override
    public @Nullable Float getFloat(@NotNull String key) {
        return null;
    }
}
