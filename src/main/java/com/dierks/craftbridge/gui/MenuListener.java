package com.dierks.craftbridge.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/**
 * Single dispatcher for every {@link Menu}. Cancels all clicks/drags in a menu except
 * plain clicks in slots the menu marked editable, and routes button clicks to the
 * menu's per-slot handlers. Registered by the plugin core, so features never need
 * their own inventory listeners for menus.
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        if (top && menu.isEditable(event.getRawSlot())) {
            // Vanilla behaviour, minus the moves that would spray items into button slots.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                    || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                return;
            }
            if (event.getWhoClicked() instanceof Player player) {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("CraftBridge");
                int slot = event.getRawSlot();
                if (plugin != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> menu.handleEditableChange(player, slot));
                }
            }
            return;
        }
        event.setCancelled(true);
        if (top) {
            menu.handleClick(event);
        } else if (event.getClick() == ClickType.DOUBLE_CLICK || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            // Already cancelled above; nothing else to do for bottom clicks.
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && !menu.isEditable(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu menu) {
            menu.handleClose(event);
        }
    }
}
