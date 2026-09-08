package com.dierks.craftbridge.workbench;

import org.bukkit.Location;
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

    /** Where {@code count} items in a grid slot came from (best effort: the first container that fed the slot). */
    public record Origin(Location block, int count) {
        public Origin plus(int more) {
            return new Origin(block, count + more);
        }

        public Origin capped(int max) {
            return count <= max ? this : new Origin(block, max);
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

    /** Record that {@code count} more items in grid slot {@code gridIndex} were pulled from {@code containerBlock}. */
    public void addOrigin(int gridIndex, Location containerBlock, int count) {
        Origin existing = origins.get(gridIndex);
        if (existing == null) {
            origins.put(gridIndex, new Origin(containerBlock.clone(), count));
        } else {
            origins.put(gridIndex, existing.plus(count));
        }
    }

    public void clearOrigin(int gridIndex) {
        origins.remove(gridIndex);
    }
}
