package com.dierks.craftbridge.jei;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;

/** Told after a JEI transfer ran on a player's open crafting view (used by the Linked Workbench). */
public interface TransferListener {
    void afterTransfer(Player player, InventoryView view, GridLayout layout, TransferPacket packet, boolean success);
}
