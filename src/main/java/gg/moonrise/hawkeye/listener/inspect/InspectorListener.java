package gg.moonrise.hawkeye.listener.inspect;

import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.inspect.InspectorService;
import gg.moonrise.hawkeye.core.service.lookup.page.LookupPager;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.ui.lookup.LookupReplies;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.block.TileStateInventoryHolder;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * The inspector's clicks. While a player inspects: left-click shows a block's history (and does not
 * break it), right-click on a block with an inventory (a container, or a lectern, jukebox, chiseled
 * bookshelf, decorated pot or shelf) shows its transactions, right-click elsewhere shows the block
 * on the clicked face, and right-click with a block in hand shows the spot that block would fill
 * (nothing is placed). A placement that arrives without a block click (a lily pad on water) or that
 * another plugin lets through is cancelled and shows its spot too. Each reply starts a lookup
 * session, so {@code /hawkeye page} continues it. The cancelling handlers run at
 * {@link EventPriority#LOWEST} so protection plugins see the cancelled event.
 */
@SpringComponent
@RequiredArgsConstructor
public final class InspectorListener implements Listener {

    private final @NotNull InspectorService inspector;
    private final @NotNull LookupPager pager;
    private final @NotNull LookupReplies replies;
    private final @NotNull HawkeyeConfigProvider configProvider;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null || !inspecting(player)) {
            return;
        }
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                event.setCancelled(true);
                lookUp(player, inspector.history(clicked));
            }
            case RIGHT_CLICK_BLOCK -> rightClick(event, player, clicked);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!inspecting(player)) {
            return;
        }
        event.setCancelled(true);
        lookUp(player, inspector.history(event.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (inspecting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        inspector.clear(event.getPlayer().getUniqueId());
    }

    private void rightClick(@NotNull PlayerInteractEvent event, @NotNull Player player, @NotNull Block clicked) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // The main hand already answered this click.
            event.setCancelled(true);
            return;
        }
        if (clicked.getState(false) instanceof TileStateInventoryHolder) {
            event.setCancelled(true);
            lookUp(player, inspector.transactions(clicked));
            return;
        }
        // Cancelling also keeps a block in hand from being placed, so no place event follows.
        event.setCancelled(true);
        lookUp(player, inspector.history(spot(event, clicked)));
    }

    /** The spot a right-click shows: the block a held block would replace, else the one on the clicked face. */
    private @NotNull Block spot(@NotNull PlayerInteractEvent event, @NotNull Block clicked) {
        if (event.isBlockInHand() && clicked.isReplaceable()) {
            return clicked;
        }
        return clicked.getRelative(event.getBlockFace());
    }

    private boolean inspecting(@NotNull Player player) {
        if (!inspector.isInspecting(player.getUniqueId())) {
            return false;
        }
        if (!player.hasPermission(Permissions.INSPECT)) {
            inspector.clear(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void lookUp(@NotNull Player player, @NotNull LookupQuery query) {
        if (inspector.shouldLookUp(player.getUniqueId(), query)) {
            replies.page(player, pager.open(player, query, configProvider.get().lookup().pageSize()));
        }
    }
}
