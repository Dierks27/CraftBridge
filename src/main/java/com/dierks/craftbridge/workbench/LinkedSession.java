package com.dierks.craftbridge.workbench;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One player's open Linked Workbench. Lives from the right-click until the vanilla
 * crafting view closes (Escape, death, teleport, walking away, quit).
 *
 * <p>{@link #origins} remembers, per crafting-grid index (0-8), which container block an
 * item was pulled from so it can be put back on close. Only container-sourced items
 * get an origin; anything the player put in themselves has none and goes back to them.
 */
public final class LinkedSession {

    private final UUID player;
    private final WorkbenchRecord record;
    private final InventoryView view;
    private final Map<Integer, Origin> origins = new HashMap<>();

    /**
     * Where {@code count} items in a grid slot came from (best effort: the first container
     * that fed the slot), and <em>which</em> item was lent.
     *
     * <p>{@code key} is the amount-1 identity of the borrowed item. Without it the two places
     * that act on an origin cannot tell whether the slot still holds what was borrowed: if
     * the player swaps the grid contents between the pull and the return, the repay path
     * would push whatever now sits there into the lender's chest. Recording the item makes
     * "is this still the thing we borrowed?" answerable.
     */
    public record Origin(Location block, int count, ItemStack key) {

        public Origin {
            key = key == null ? null : StorageScanner.keyOf(key);
        }

        public Origin plus(int more) {
            return new Origin(block, count + more, key);
        }

        public Origin capped(int max) {
            return count <= max ? this : new Origin(block, max, key);
        }

        /** True when {@code stack} is the item this origin lent out. */
        public boolean matches(ItemStack stack) {
            if (key == null) {
                return true; // an origin recorded before the item was tracked: behave as before
            }
            return stack != null && key.isSimilar(stack);
        }
    }

    public LinkedSession(UUID player, WorkbenchRecord record, InventoryView view) {
        this.player = player;
        this.record = record;
        this.view = view;
    }

    public UUID player() {
        return player;
    }

    public WorkbenchRecord record() {
        return record;
    }

    public InventoryView view() {
        return view;
    }

    /** Center of the table block. */
    public Location tableCenter() {
        Location loc = record.location();
        return loc == null ? null : loc.add(0.5, 0.5, 0.5);
    }

    public Map<Integer, Origin> origins() {
        return origins;
    }

    /**
     * Record that {@code count} more items in grid slot {@code gridIndex} were pulled from
     * {@code containerBlock}. An existing origin for a <em>different</em> item is replaced
     * rather than added to: the slot no longer holds what was borrowed, so the old debt can
     * never be repaid from it and keeping it would repay the wrong item.
     */
    public void addOrigin(int gridIndex, Location containerBlock, int count, ItemStack borrowed) {
        Origin existing = origins.get(gridIndex);
        if (existing == null || !existing.matches(borrowed)) {
            origins.put(gridIndex, new Origin(containerBlock.clone(), count, borrowed));
        } else {
            origins.put(gridIndex, existing.plus(count));
        }
    }

    public void clearOrigin(int gridIndex) {
        origins.remove(gridIndex);
    }
}
