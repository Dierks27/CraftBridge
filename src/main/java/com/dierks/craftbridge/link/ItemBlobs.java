package com.dierks.craftbridge.link;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.inventory.ItemStack;

/**
 * Turns an item stack into the bytes the CraftBridge link carries, and back.
 *
 * <p>The encoding is the game's own network codec for a slot's contents — the same one every
 * vanilla packet carrying an item uses — so the mod on the other end decodes it with the
 * client's copy of that codec and neither side has to know what a component means. That codec
 * is server internals, so the implementation lives in {@code link.nms} and is loaded by name:
 * on a server whose internals moved, the link switches off and the plugin carries on.
 */
public interface ItemBlobs {

    byte[] encode(ItemStack stack);

    /** Null when the bytes are not an item this server can read. */
    ItemStack decode(byte[] bytes);

    static ItemBlobs create(CraftBridgePlugin plugin) {
        try {
            return (ItemBlobs) Class.forName("com.dierks.craftbridge.link.nms.PaperItemBlobs")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            plugin.getLogger().warning("CraftBridge client link disabled: the server-internals item codec failed to load ("
                    + t + "). Clients with the mod will stay dormant and JEI keeps using phantom slots.");
            return null;
        }
    }
}
