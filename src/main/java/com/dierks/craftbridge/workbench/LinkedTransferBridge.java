package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.jei.GridLayout;
import com.dierks.craftbridge.jei.TransferEngine;
import com.dierks.craftbridge.jei.TransferListener;
import com.dierks.craftbridge.jei.TransferPacket;
import com.dierks.craftbridge.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 1 + Feature 2: JEI {@code [+]} inside a Linked Workbench session.
 *
 * <p>JEI's client only sends a transfer when every ingredient is in the player's
 * inventory (or already in the grid), and the server-side algorithm draws from the
 * player's inventory first — that part is unchanged. What this bridge adds is the
 * second source: after a <em>shift</em>-{@code [+]} (maxTransfer) has filled as many
 * sets as the player's own items allow, the grid is topped up with more sets from nearby
 * storage, taking items out of the containers at transfer time and remembering, per grid
 * slot, which container they came from so they can go back on close.
 *
 * <p>Ingredients the player does not carry at all cannot be sourced this way: JEI greys
 * out the button client-side before anything reaches the server. The storage GUI
 * (sneak + right-click) is the way to fetch those first.
 */
public final class LinkedTransferBridge implements TransferListener {

    private final CraftBridgePlugin plugin;
    private final WorkbenchFeature feature;

    public LinkedTransferBridge(CraftBridgePlugin plugin, WorkbenchFeature feature) {
        this.plugin = plugin;
        this.feature = feature;
    }

    @Override
    public Map<Integer, TransferEngine.Stack<ItemStack>> virtualSlots(Player player, InventoryView view, GridLayout layout) {
        PhantomManager phantoms = feature.phantoms();
        return phantoms == null ? Map.of() : phantoms.virtualSlots(player, view);
    }

    @Override
    public Map<ItemStack, Integer> settleVirtual(Player player, InventoryView view, GridLayout layout,
                                                 Map<Integer, TransferEngine.Stack<ItemStack>> virtualBefore,
                                                 Map<Integer, TransferEngine.Stack<ItemStack>> resultSlots) {
        PhantomManager phantoms = feature.phantoms();
        return phantoms == null ? Map.of() : phantoms.settle(player, view, layout, virtualBefore, resultSlots);
    }

    @Override
    public void afterTransfer(Player player, InventoryView view, GridLayout layout, TransferPacket packet, boolean success) {
        LinkedSession session = feature.sessions().of(player);
        if (session == null || session.view() != view) {
            return;
        }
        if (feature.phantoms() != null) {
            feature.phantoms().rebuildLater(player);
        }
        if (!success) {
            return;
        }
        if (!(view.getTopInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        ItemStack[] matrix = crafting.getMatrix();
        reconcileOrigins(session, matrix);
        if (!packet.maxTransfer()) {
            return;
        }
        int pulled = topUp(player, session, matrix, packet.requireCompleteSets());
        if (pulled > 0) {
            crafting.setMatrix(matrix);
            player.updateInventory();
            plugin.debug("Linked Workbench: topped up " + pulled + " item(s) from nearby storage for " + player.getName());
        }
    }

    /**
     * Drop origins whose slot no longer holds the item they described (JEI moved it out).
     *
     * <p>"The item they described" is checked properly: an origin records which stack it lent,
     * so a slot that now holds a <em>different</em> item drops its origin instead of being
     * capped. Without that check a [+] that swapped one ingredient for another would leave the
     * debt attached to the new item, and the close path would post it to the wrong chest.
     */
    private void reconcileOrigins(LinkedSession session, ItemStack[] matrix) {
        for (Map.Entry<Integer, LinkedSession.Origin> e : new HashMap<>(session.origins()).entrySet()) {
            int index = e.getKey();
            if (index < 0 || index >= matrix.length || Items.isEmpty(matrix[index])
                    || !e.getValue().matches(matrix[index])) {
                session.clearOrigin(index);
                continue;
            }
            session.origins().put(index, e.getValue().capped(matrix[index].getAmount()));
        }
    }

    /**
     * Fill the grid with more sets from storage. With {@code completeSets} every filled
     * slot gains the same number of items (limited by the scarcest ingredient and the
     * smallest stack limit); otherwise each slot is filled independently.
     */
    private int topUp(Player player, LinkedSession session, ItemStack[] matrix, boolean completeSets) {
        List<StorageScanner.Source> sources = feature.scanner().scan(player, session.record().location(),
                plugin.config().workbenchRadius());
        if (sources.isEmpty()) {
            return 0;
        }
        // Slots that share an ingredient share its pool.
        Map<ItemStack, Integer> need = new HashMap<>();
        Map<ItemStack, Integer> pool = new HashMap<>();
        for (ItemStack stack : matrix) {
            if (Items.isEmpty(stack)) {
                continue;
            }
            ItemStack key = StorageScanner.keyOf(stack);
            need.merge(key, 1, Integer::sum);
            pool.computeIfAbsent(key, k -> StorageScanner.count(sources, k));
        }
        if (need.isEmpty()) {
            return 0;
        }
        int gainPerSlot = Integer.MAX_VALUE;
        if (completeSets) {
            for (int i = 0; i < matrix.length; i++) {
                ItemStack stack = matrix[i];
                if (Items.isEmpty(stack)) {
                    continue;
                }
                ItemStack key = StorageScanner.keyOf(stack);
                int room = maxStack(stack) - stack.getAmount();
                int share = pool.get(key) / need.get(key);
                gainPerSlot = Math.min(gainPerSlot, Math.min(room, share));
            }
            if (gainPerSlot <= 0) {
                return 0;
            }
        }
        int pulledTotal = 0;
        for (int i = 0; i < matrix.length; i++) {
            ItemStack stack = matrix[i];
            if (Items.isEmpty(stack)) {
                continue;
            }
            int wanted = completeSets ? gainPerSlot : maxStack(stack) - stack.getAmount();
            if (wanted <= 0) {
                continue;
            }
            ItemStack key = StorageScanner.keyOf(stack);
            int pulled = 0;
            for (StorageScanner.Pulled p : feature.scanner().pull(sources, key, wanted)) {
                pulled += p.stack().getAmount();
                session.addOrigin(i, p.source().location(), p.stack().getAmount(), p.stack());
            }
            if (pulled > 0) {
                stack.setAmount(stack.getAmount() + pulled);
                matrix[i] = stack;
                pulledTotal += pulled;
            }
        }
        return pulledTotal;
    }

    private static int maxStack(ItemStack stack) {
        return Math.max(1, Math.min(stack.getMaxStackSize(), 64));
    }
}
