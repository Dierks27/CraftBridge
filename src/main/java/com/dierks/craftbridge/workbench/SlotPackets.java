package com.dierks.craftbridge.workbench;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Packet-only slot updates for the player's currently open container menu. Nothing here
 * touches the real inventory; it only changes what the client displays. Implemented with
 * server internals in {@code workbench.nms} and loaded reflectively.
 */
public interface SlotPackets {

    /** Show {@code item} in raw slot {@code rawSlot} of the open menu (client-side only). */
    void sendSlot(Player player, int rawSlot, ItemStack item);

    /** Re-send what the server really has in raw slot {@code rawSlot} of the open menu. */
    void sendRealSlot(Player player, int rawSlot);
}
