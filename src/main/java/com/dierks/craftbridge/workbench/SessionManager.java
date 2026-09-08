package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Opens linked crafting views and tracks the per-player {@link LinkedSession}. */
public final class SessionManager {

    private final CraftBridgePlugin plugin;
    private final Map<UUID, LinkedSession> sessions = new HashMap<>();

    public SessionManager(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    public LinkedSession of(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /**
     * Open a real vanilla crafting menu attached to the table (so it closes when the
     * player walks more than 8 blocks away or the table is gone, and so JEI recognises it
     * as MenuType.CRAFTING) and start the session.
     */
    public LinkedSession open(Player player, WorkbenchRecord record) {
        end(player, false);
        Location loc = record.location();
        InventoryView view = MenuType.CRAFTING.builder()
                .location(loc)
                .checkReachable(true)
                .title(Component.text("Linked Workbench"))
                .build(player);
        player.openInventory(view);
        LinkedSession session = new LinkedSession(player.getUniqueId(), record, view);
        sessions.put(player.getUniqueId(), session);
        return session;
    }

    /** Is this view the player's linked crafting view? */
    public boolean isLinkedView(Player player, InventoryView view) {
        LinkedSession session = of(player);
        return session != null && session.view() == view;
    }

    /**
     * End the session. With {@code returnToOrigins}, grid items that came from a container
     * go back there if it has room; whatever stays in the grid is handed to the player
     * by vanilla when the menu closes.
     */
    public void end(Player player, boolean returnToOrigins) {
        LinkedSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!returnToOrigins || session.origins().isEmpty()) {
            return;
        }
        Inventory top = session.view().getTopInventory();
        if (!(top instanceof CraftingInventory crafting)) {
            return;
        }
        ItemStack[] matrix = crafting.getMatrix();
        boolean changed = false;
        for (Map.Entry<Integer, Location> e : session.origins().entrySet()) {
            int index = e.getKey();
            if (index < 0 || index >= matrix.length || Items.isEmpty(matrix[index])) {
                continue;
            }
            Block block = e.getValue().getBlock();
            if (!(block.getState(false) instanceof Container container)) {
                continue;
            }
            Map<Integer, ItemStack> left = container.getInventory().addItem(matrix[index]);
            matrix[index] = left.isEmpty() ? null : left.values().iterator().next();
            changed = true;
        }
        if (changed) {
            crafting.setMatrix(matrix);
        }
    }

    public void endAll() {
        for (UUID id : new java.util.ArrayList<>(sessions.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                end(p, true);
                p.closeInventory();
            }
        }
        sessions.clear();
    }
}
