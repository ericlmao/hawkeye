package gg.moonrise.hawkeye.ui.dialog;

import gg.moonrise.hawkeye.util.compat.NewerApi;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Shows dialogs (Minecraft 1.21.6+ dialog screens) to players. The Paper implementation needs
 * {@link NewerApi#DIALOG}; older servers get one that shows nothing, so callers fall back to chat.
 */
public interface DialogScreens {

    /**
     * Whether this server can show dialogs at all.
     *
     * @return false on servers without Paper's dialog API
     */
    boolean supported();

    /**
     * Shows a dialog. Call on the player's thread.
     *
     * @param player who sees it
     * @param screen what it shows
     * @return true when the dialog was sent, false when nothing was shown
     */
    boolean show(@NotNull Player player, @NotNull DialogScreen screen);
}
