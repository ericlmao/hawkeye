package gg.moonrise.hawkeye.ui.dialog;

import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@link DialogScreens} on a server from before Paper 1.21.7, which cannot show dialogs. */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.DIALOG)
public final class NoDialogScreens implements DialogScreens {

    @Override
    public boolean supported() {
        return false;
    }

    @Override
    public boolean show(@NotNull Player player, @NotNull DialogScreen screen) {
        return false;
    }
}
