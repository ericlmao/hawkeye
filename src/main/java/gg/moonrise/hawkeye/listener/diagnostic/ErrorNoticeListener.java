package gg.moonrise.hawkeye.listener.diagnostic;

import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.core.service.diagnostic.ErrorReportService;
import gg.moonrise.hawkeye.ui.diagnostic.ReportReplies;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Tells a player with {@link Permissions#ERRORS} (OPs by default) on join how many error and crash files wait to be
 * reviewed ({@code error-reports.join-notice}). The folders are listed on the query pool (the files are not read);
 * the notice is sent on the player's own thread, and not at all when nothing waits.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ErrorNoticeListener implements Listener {

    private final @NotNull ErrorReportService reports;
    private final @NotNull ReportReplies replies;

    /**
     * Sends the notice.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (reports.joinNotice() && player.hasPermission(Permissions.ERRORS)) {
            replies.joinNotice(player, reports.pendingCount());
        }
    }
}
