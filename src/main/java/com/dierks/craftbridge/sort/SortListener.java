package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.integration.ContainerAccess;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns clicks into sorts according to each player's {@link SortTrigger}.
 *
 * <p>Why the outside-click triggers are fuzzy: the vanilla client sends a click on the
 * dark area outside a GUI as a THROW-mode click with slot -999 and does not include
 * the shift state, and while a screen is open the client does not report sneaking
 * either. So {@link SortTrigger#SHIFT_CLICK_OUTSIDE} accepts SHIFT_LEFT/SHIFT_RIGHT
 * <em>or</em> a plain LEFT/RIGHT while {@code isSneaking()} — whichever the 26.2
 * client turns out to send. {@code /sort debug} prints the raw click so this can be
 * confirmed on the live server.
 */
public final class SortListener implements Listener {

    private final CraftBridgePlugin plugin;
    private final SortFeature feature;
    private final Map<UUID, Long> lastOutsideClick = new HashMap<>();
    private final Set<UUID> debugging;

    public SortListener(CraftBridgePlugin plugin, SortFeature feature, Set<UUID> debugging) {
        this.plugin = plugin;
        this.feature = feature;
        this.debugging = debugging;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        if (view.getTopInventory().getHolder(false) instanceof Menu) {
            return;
        }
        if (event.getSlotType() != InventoryType.SlotType.OUTSIDE) {
            return;
        }
        if (debugging.contains(player.getUniqueId())) {
            player.sendMessage(Text.msg("<gray>click outside: type=<white>" + event.getClick()
                    + "<gray> action=<white>" + event.getAction() + "<gray> raw=<white>" + event.getRawSlot()
                    + "<gray> cursor=<white>" + Items.describe(event.getCursor())
                    + "<gray> sneaking=<white>" + player.isSneaking()
                    + "<gray> view=<white>" + view.getType()));
        }
        if (!Items.isEmpty(event.getCursor())) {
            return; // clicking outside with an item = drop it; never sort then
        }
        if (!player.hasPermission("craftbridge.sort")) {
            return;
        }
        PlayerSortSettings settings = feature.settings(player);
        ClickType click = event.getClick();
        boolean fire;
        switch (settings.trigger()) {
            case SHIFT_CLICK_OUTSIDE -> fire = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT
                    || ((click == ClickType.LEFT || click == ClickType.RIGHT) && player.isSneaking());
            case DOUBLE_CLICK_OUTSIDE -> {
                long now = System.currentTimeMillis();
                Long last = lastOutsideClick.put(player.getUniqueId(), now);
                fire = last != null && now - last <= plugin.config().sortDoubleClickMillis();
                if (fire) {
                    lastOutsideClick.remove(player.getUniqueId());
                }
            }
            default -> fire = false;
        }
        if (fire) {
            feature.sortOpenInventory(player, view);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPunch(PlayerInteractEvent event) {
        if (ContainerAccess.isSynthetic() || event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("craftbridge.sort")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !feature.service().isSortableBlock(block.getType())) {
            return;
        }
        if (feature.settings(player).trigger() != SortTrigger.SNEAK_PUNCH_BLOCK) {
            return;
        }
        event.setCancelled(true); // no block damage / no punch animation to others
        if (!(block.getState(false) instanceof Container container)) {
            return;
        }
        if (!plugin.containerAccess().canUse(player, block, plugin.config().sortRespectProtection())) {
            player.sendMessage(Text.msg("<red>You can't use that container."));
            return;
        }
        Inventory inventory = container.getInventory(); // whole double chest for either half
        feature.sortNow(player, inventory, block.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOutsideClick.remove(event.getPlayer().getUniqueId());
        debugging.remove(event.getPlayer().getUniqueId());
    }
}
