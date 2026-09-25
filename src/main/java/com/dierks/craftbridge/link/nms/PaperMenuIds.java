package com.dierks.craftbridge.link.nms;

import com.dierks.craftbridge.link.MenuIds;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * NMS implementation (Mojang mappings via paperweight-userdev): the same
 * {@code getHandle().containerMenu.containerId} that {@code workbench.nms.PaperSlotPackets}
 * already reads to address its slot packets.
 */
public final class PaperMenuIds implements MenuIds {

    @Override
    public int openContainerId(Player player) {
        return ((CraftPlayer) player).getHandle().containerMenu.containerId;
    }
}
