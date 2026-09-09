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
    private PhantomManager phantoms;

    public SessionManager(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    void setPhantoms(PhantomManager phantoms) {
        this.phantoms = phantoms;
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
        if (phantoms != null) {
            phantoms.start(player); // a no-op for a player whose own client shows them storage
        }
        link(link -> link.sessionOpened(player));
        return session;
    }

    /** Is this view the player's linked crafting view? */
    public boolean isLinkedView(Player player, InventoryView view) {
        LinkedSession session = of(player);
        return session != null && session.view() == view;
    }

    /**
     * End the session and empty the crafting grid in a defined order, rather than leaving it
     * to whenever vanilla's own close handling runs:
     *
     * <ol>
     *   <li>items that came from a container go back to that container;</li>
     *   <li>whatever it will not take goes to the player;</li>
     *   <li>anything the player cannot hold is dropped at the table.</li>
     * </ol>
     *
     * Items the player put in by hand are theirs and go straight to step 2. Draining the grid
     * ourselves is what makes the order deterministic: vanilla hands the whole grid back to
     * the player when a crafting menu closes, so anything still in it by then has skipped
     * step 1 — which is why storage items were coming back to the player instead of the chest.
     */
    public void end(Player player, boolean returnToOrigins) {
        LinkedSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        link(l -> l.sessionEnded(player, "the workbench was closed"));
        if (phantoms != null) {
            // The menu is closing: re-sync next tick so the inventory screen shows real contents only.
            phantoms.end(player, false);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.updateInventory();
                }
            });
        }
        drainGrid(player, session, returnToOrigins);
    }

    /**
     * Empty the crafting grid the same way a close does: owed items back to the container they
     * came from, the rest to the player, anything they cannot hold dropped at the table. Used
     * on close, and before a client-link transfer refills the grid.
     */
    public void drainGrid(Player player, LinkedSession session, boolean returnToOrigins) {
        if (!(session.view().getTopInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        ItemStack[] matrix = crafting.getMatrix();
        boolean changed = false;

        if (returnToOrigins) {
            for (Map.Entry<Integer, LinkedSession.Origin> e : session.origins().entrySet()) {
                int index = e.getKey();
                if (index < 0 || index >= matrix.length || Items.isEmpty(matrix[index])) {
                    continue;
                }
                Block block = e.getValue().block().getBlock();
                if (!(block.getState(false) instanceof Container container)) {
                    continue;
                }
                ItemStack present = matrix[index];
                int owed = Math.min(e.getValue().count(), present.getAmount());
                if (owed <= 0) {
                    continue;
                }
                ItemStack giveBack = present.clone();
                giveBack.setAmount(owed);
                Map<Integer, ItemStack> left = container.getInventory().addItem(giveBack);
                int notTaken = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                int returned = owed - notTaken;
                if (returned <= 0) {
                    continue;
                }
                int remaining = present.getAmount() - returned;
                matrix[index] = remaining <= 0 ? null : present.clone().asQuantity(remaining);
                changed = true;
            }
        }

        // Whatever is still in the grid is the player's: hand it over, and drop what they
        // cannot hold rather than letting a later close path decide.
        for (int i = 0; i < matrix.length; i++) {
            ItemStack rest = matrix[i];
            if (Items.isEmpty(rest)) {
                continue;
            }
            matrix[i] = null;
            changed = true;
            for (ItemStack over : player.getInventory().addItem(rest).values()) {
                player.getWorld().dropItemNaturally(dropSpot(player, session), over);
            }
        }
        session.origins().clear();
        if (changed) {
            crafting.setMatrix(matrix);
        }
    }

    /** Tell the client link about a session change, when the link is switched on at all. */
    private void link(java.util.function.Consumer<com.dierks.craftbridge.link.LinkFeature> action) {
        com.dierks.craftbridge.link.LinkFeature feature =
                plugin.feature(com.dierks.craftbridge.link.LinkFeature.class);
        if (feature != null) {
            action.accept(feature);
        }
    }

    /** At the table if we still know where it is, else at the player. */
    private static org.bukkit.Location dropSpot(Player player, LinkedSession session) {
        org.bukkit.Location table = session.record() == null ? null : session.record().location();
        return table == null ? player.getLocation() : table.clone().add(0.5, 1.0, 0.5);
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
