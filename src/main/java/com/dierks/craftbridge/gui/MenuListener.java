package com.dierks.craftbridge.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Single dispatcher for every {@link Menu}.
 *
 * <ul>
 *   <li>Clicks on menu buttons are cancelled and routed to the menu's slot handler.</li>
 *   <li>Clicks in slots the menu marked editable keep vanilla behaviour (pick up / put
 *       down / hotbar swap), minus shift-moves and double-click collects that would spray
 *       items across button slots. Clicking an <em>empty</em> editable slot with an empty
 *       cursor does nothing in vanilla, so it is handed to the menu instead — that is how
 *       the recipe editor opens its item picker.</li>
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
        if (refuseWithoutPermission(menu, event.getWhoClicked(), event)) {
            return;
        }
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        boolean bulkMove = event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (menu.handleDepositsAccepted() && event.getWhoClicked() instanceof Player depositor) {
            ItemStack cursor = event.getCursor();
            if (top && cursor != null && !cursor.isEmpty()) {
                // Clicking anywhere in the menu with an item in hand = deposit it.
                event.setCancelled(true);
                event.getView().setCursor(menu.handleDeposit(depositor, cursor.clone()));
                return;
            }
            ItemStack current = event.getCurrentItem();
            if (!top && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && current != null && !current.isEmpty()) {
                // Shift-click from the player's inventory = deposit that stack.
                event.setCancelled(true);
                event.setCurrentItem(menu.handleDeposit(depositor, current.clone()));
                return;
            }
        }
        if (top) {
            if (menu.isEditable(event.getRawSlot())) {
                ItemStack cursor = event.getCursor();
                ItemStack inSlot = event.getCurrentItem();
                boolean emptyHanded = (cursor == null || cursor.isEmpty()) && (inSlot == null || inSlot.isEmpty());
                if (emptyHanded && menu.hasHandler(event.getRawSlot())) {
                    // Empty hand on an empty input slot: vanilla would do nothing, so the menu
                    // gets it instead (the recipe editor opens its item picker there).
                    event.setCancelled(true);
                    menu.handleClick(event);
                    return;
                }
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
        if (refuseWithoutPermission(menu, event.getWhoClicked(), event)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (menu.handleDepositsAccepted() && event.getWhoClicked() instanceof Player depositor) {
            for (int raw : event.getRawSlots()) {
                if (raw < topSize) {
                    // Dragging over the menu = deposit the whole cursor stack (next tick, after the
                    // cancelled drag has restored it).
                    event.setCancelled(true);
                    ItemStack dragged = event.getOldCursor().clone();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (depositor.isOnline() && depositor.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                            depositor.setItemOnCursor(menu.handleDeposit(depositor, dragged));
                        }
                    });
                    return;
                }
            }
        }
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

    /**
     * Cancel and close when the viewer no longer holds the menu's permission. Returns true
     * when the interaction was refused.
     */
    private boolean refuseWithoutPermission(Menu menu, org.bukkit.entity.HumanEntity who,
                                            org.bukkit.event.Cancellable event) {
        String node = menu.permissionNode();
        if (node == null || !(who instanceof Player player) || player.hasPermission(node)) {
            return false;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            player.sendMessage(com.dierks.craftbridge.util.Text.msg(
                    "<red>You no longer have permission to use that."));
        });
        return true;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            menu.handleClose(event);
        }
    }
}
