package com.dierks.craftbridge.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Click-driven chest-GUI base. Every CraftBridge menu is one of these and a single
 * {@link MenuListener} dispatches to it. Slots carry click handlers; clicks on slots
 * without a handler are cancelled and ignored, so nothing can be dragged out.
 *
 * <p>Menus that need real item input (the recipe editor) mark slots as
 * {@link #editable(int...)}: vanilla click behaviour is allowed in those slots only.
 * Plain chest layouts everywhere so Geyser/Bedrock players get the same GUIs.
 */
public abstract class Menu implements InventoryHolder {

    private Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private final Set<Integer> editableSlots = new HashSet<>();

    protected void init(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Populate items + handlers. Called on open and on {@link #refresh()}. */
    protected abstract void build();

    /** Place an item, optionally with a click handler (null = decorative/locked). */
    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> onClick) {
        inventory.setItem(slot, item);
        if (onClick != null) {
            handlers.put(slot, onClick);
        } else {
            handlers.remove(slot);
        }
    }

    protected void fill(ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    /** Slots where the player may put/take real items (vanilla click behaviour). */
    protected void editable(int... slots) {
        for (int s : slots) {
            editableSlots.add(s);
        }
    }

    public boolean isEditable(int rawSlot) {
        return editableSlots.contains(rawSlot);
    }

    /** Rebuild the menu in place (contents update live for anyone viewing it). */
    protected void refresh() {
        handlers.clear();
        // Keep whatever is in editable slots: those are the player's real items.
        for (int i = 0; i < inventory.getSize(); i++) {
            if (!editableSlots.contains(i)) {
                inventory.setItem(i, null);
            }
        }
        build();
    }

    public void open(Player player) {
        build();
        player.openInventory(inventory);
    }

    /** Called after any click in an editable slot has been applied (next tick). */
    protected void onEditableSlotChanged(Player player, int rawSlot) {
    }

    /** Called when the viewer closes the menu. */
    protected void onClose(InventoryCloseEvent event) {
    }

    void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(event);
        }
    }

    void handleClose(InventoryCloseEvent event) {
        onClose(event);
    }

    void handleEditableChange(Player player, int rawSlot) {
        onEditableSlotChanged(player, rawSlot);
    }
}
