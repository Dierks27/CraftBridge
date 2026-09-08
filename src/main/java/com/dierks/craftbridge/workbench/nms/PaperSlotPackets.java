package com.dierks.craftbridge.workbench.nms;

import com.dierks.craftbridge.workbench.SlotPackets;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * NMS implementation (Mojang mappings via paperweight-userdev). The call is the same one
 * CraftBukkit's own {@code CraftInventoryPlayer#setItem} uses to sync a slot:
 * {@code new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, stack)}.
 * Raw Bukkit view slots and container-menu slot indexes are the same numbering.
 */
public final class PaperSlotPackets implements SlotPackets {

    @Override
    public void sendSlot(Player player, int rawSlot, ItemStack item) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        AbstractContainerMenu menu = handle.containerMenu;
        if (rawSlot < 0 || rawSlot >= menu.slots.size()) {
            return;
        }
        handle.connection.send(new ClientboundContainerSetSlotPacket(
                menu.containerId, menu.incrementStateId(), rawSlot, CraftItemStack.asNMSCopy(item)));
    }

    @Override
    public void sendRealSlot(Player player, int rawSlot) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        AbstractContainerMenu menu = handle.containerMenu;
        if (rawSlot < 0 || rawSlot >= menu.slots.size()) {
            return;
        }
        handle.connection.send(new ClientboundContainerSetSlotPacket(
                menu.containerId, menu.incrementStateId(), rawSlot, menu.getSlot(rawSlot).getItem().copy()));
    }
}
