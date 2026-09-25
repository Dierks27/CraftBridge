package com.dierks.craftbridge.link;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.entity.Player;

/**
 * The id of the menu a player has open, as their client knows it. Bukkit has no API for it,
 * so the implementation lives in {@code link.nms} and is loaded by name, like {@link ItemBlobs}:
 * on a server whose internals moved, middle-click sorting switches off and nothing else does.
 */
public interface MenuIds {

    int openContainerId(Player player);

    static MenuIds create(CraftBridgePlugin plugin) {
        try {
            return (MenuIds) Class.forName("com.dierks.craftbridge.link.nms.PaperMenuIds")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            plugin.getLogger().warning("CraftBridge client link: middle-click sorting disabled; menu ids are"
                    + " unavailable on this server (" + t + ").");
            return null;
        }
    }
}
