package com.dierks.craftbridge.gui;

import com.dierks.craftbridge.util.Text;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Single dispatcher for every {@link Menu}. What each gesture means is decided by
 * {@link MenuClicks}, which is a plain table with a test per cell; this class only carries
 * the decision out.
 *
 * <p>The important half of that table is what it does <em>not</em> refuse: clicks in the
 * player's own inventory are ordinary vanilla clicks, so an item can always be picked up onto
 * the cursor while a menu is open. Only shift-clicks and double-click collects — the two
 * gestures that reach up into the menu — are handled specially.
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
        int raw = event.getRawSlot();
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        MenuClicks.Region region;
        if (event.getClickedInventory() == null) {
            region = MenuClicks.Region.OUTSIDE;
        } else if (top) {
            region = menu.isEditable(raw) ? MenuClicks.Region.TOP_EDITABLE : MenuClicks.Region.TOP_BUTTON;
        } else {
            region = MenuClicks.Region.BOTTOM;
        }

        ItemStack cursor = event.getCursor();
        ItemStack inSlot = event.getCurrentItem();
        MenuClicks.Action action = MenuClicks.decide(region, event.getClick().name(),
                isEmpty(cursor), isEmpty(inSlot),
                new MenuClicks.Traits(menu.handleDepositsAccepted(), menu.hasHandler(raw)));

        Player player = event.getWhoClicked() instanceof Player p ? p : null;
        switch (action) {
            case VANILLA -> {
            }
            case CANCEL -> event.setCancelled(true);
            case BUTTON, PICKER -> {
                event.setCancelled(true);
                menu.handleClick(event);
            }
            case EDITABLE -> {
                if (player != null) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> menu.handleEditableChange(player, raw));
                }
            }
            case DEPOSIT_ALL -> {
                event.setCancelled(true);
                if (player != null) {
                    event.getView().setCursor(menu.handleDeposit(player, cursor.clone()));
                }
            }
            case DEPOSIT_ONE -> {
                event.setCancelled(true);
                if (player != null) {
                    event.getView().setCursor(depositOne(menu, player, cursor));
                }
            }
            case DEPOSIT_SLOT -> {
                event.setCancelled(true);
                if (player != null) {
                    event.setCurrentItem(menu.handleDeposit(player, inSlot.clone()));
                }
            }
        }
    }

    /** Right-click deposit: one item off the cursor, and the cursor keeps the rest. */
    private static ItemStack depositOne(Menu menu, Player player, ItemStack cursor) {
        ItemStack one = cursor.clone();
        one.setAmount(1);
        ItemStack left = menu.handleDeposit(player, one);
        int keep = MenuClicks.keptOnCursor(cursor.getAmount(), 1, isEmpty(left) ? 0 : left.getAmount());
        if (keep <= 0) {
            return null;
        }
        ItemStack kept = cursor.clone();
        kept.setAmount(keep);
        return kept;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        if (refuseWithoutPermission(menu, event.getWhoClicked(), event)) {
            return;
        }
        int menuSize = event.getView().getTopInventory().getSize();
        boolean touchesMenu = false;
        boolean touchesNonEditable = false;
        for (int raw : event.getRawSlots()) {
            if (raw < menuSize) {
                touchesMenu = true;
                touchesNonEditable |= !menu.isEditable(raw);
            }
        }

        MenuClicks.Drag decision = MenuClicks.decideDrag(touchesMenu, touchesNonEditable,
                menu.handleDepositsAccepted());
        if (decision == MenuClicks.Drag.ALLOW) {
            return;
        }
        event.setCancelled(true);
        if (decision == MenuClicks.Drag.CANCEL || !(event.getWhoClicked() instanceof Player depositor)) {
            return;
        }

        ItemStack dragged = event.getOldCursor().clone();
        int aimedAtMenu = MenuClicks.dragIntoMenu(addedAmounts(event), menuSize);
        // Nothing measurably destined for the menu (every covered slot was already occupied):
        // the gesture was still aimed at the menu, so treat it as depositing the stack.
        int amount = aimedAtMenu > 0 ? Math.min(aimedAtMenu, dragged.getAmount()) : dragged.getAmount();
        // The drag is cancelled, so the cursor is whole again next tick; deposit its share then.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!depositor.isOnline() || depositor.getOpenInventory().getTopInventory().getHolder(false) != menu) {
                return;
            }
            ItemStack toDeposit = dragged.clone();
            toDeposit.setAmount(amount);
            ItemStack left = menu.handleDeposit(depositor, toDeposit);
            int keep = MenuClicks.keptOnCursor(dragged.getAmount(), amount, isEmpty(left) ? 0 : left.getAmount());
            if (keep <= 0) {
                depositor.setItemOnCursor(null);
                return;
            }
            ItemStack kept = dragged.clone();
            kept.setAmount(keep);
            depositor.setItemOnCursor(kept);
        });
    }

    /** How many items each raw slot would gain from this drag. */
    private static Map<Integer, Integer> addedAmounts(InventoryDragEvent event) {
        Map<Integer, Integer> added = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            ItemStack before = event.getView().getItem(rawSlot);
            int had = isEmpty(before) ? 0 : before.getAmount();
            added.put(rawSlot, entry.getValue().getAmount() - had);
        }
        return added;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /**
     * Cancel and close when the viewer no longer holds the menu's permission. Returns true
     * when the interaction was refused.
     */
    private boolean refuseWithoutPermission(Menu menu, HumanEntity who, Cancellable event) {
        String node = menu.permissionNode();
        if (node == null || !(who instanceof Player player) || player.hasPermission(node)) {
            return false;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            player.sendMessage(Text.msg("<red>You no longer have permission to use that."));
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
