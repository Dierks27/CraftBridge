package com.dierks.craftbridge.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/**
 * Single dispatcher for every {@link Menu}.
 *
 * <ul>
 *   <li>Clicks on menu buttons are cancelled and routed to the menu's slot handler.</li>
 *   <li>Clicks in slots the menu marked editable keep vanilla behaviour (pick up / put
 *       down / hotbar swap), minus shift-moves and double-click collects that would spray
 *       items across button slots.</li>
 *   <li>Clicks in the player's own inventory are only allowed when the menu has editable
 *       slots (so items can be picked up for it), again minus shift-move / collect.</li>
 * </ul>
 */
public final class MenuListener implements Listener {

    private final Plugin plugin;

    public MenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        boolean bulkMove = event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (top) {
            if (menu.isEditable(event.getRawSlot())) {
                if (bulkMove) {
                    event.setCancelled(true);
                    return;
                }
                if (event.getWhoClicked() instanceof Player player) {
                    int slot = event.getRawSlot();
                    plugin.getServer().getScheduler().runTask(plugin, () -> menu.handleEditableChange(player, slot));
                }
                return;
            }
            event.setCancelled(true);
            menu.handleClick(event);
            return;
        }
        // Bottom (player) inventory.
        if (!menu.hasEditableSlots() || bulkMove) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !menu.isEditable(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        if (!menu.hasEditableSlots()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            menu.handleClose(event);
        }
    }
}
