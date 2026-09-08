package com.dierks.craftbridge.jei;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Hooks around a JEI transfer on a player's open crafting view (used by the Linked
 * Workbench). {@link #virtualSlots} lets a feature tell the engine that some empty
 * inventory slots "hold" items that really live elsewhere; {@link #settleVirtual} is
 * then asked to make the engine's result true (pull the real items in, put stowed
 * items back) and to report what could not be delivered.
 */
public interface TransferListener {

    /** Raw inventory slot → stack the engine should treat as present there. Only empty real slots are overlaid. */
    default Map<Integer, TransferEngine.Stack<ItemStack>> virtualSlots(Player player, InventoryView view, GridLayout layout) {
        return Map.of();
    }

    /**
     * Apply the engine's changes to virtual slots for real. Returns, per item key, how many
     * items the engine took from virtual slots that could NOT actually be provided; the
     * feature trims the grid by that much.
     */
    default Map<ItemStack, Integer> settleVirtual(Player player, InventoryView view, GridLayout layout,
                                                  Map<Integer, TransferEngine.Stack<ItemStack>> virtualBefore,
                                                  Map<Integer, TransferEngine.Stack<ItemStack>> resultSlots) {
        return Map.of();
    }

    void afterTransfer(Player player, InventoryView view, GridLayout layout, TransferPacket packet, boolean success);
}
