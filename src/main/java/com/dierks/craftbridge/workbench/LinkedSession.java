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
    private final Map<Integer, Location> origins = new HashMap<>();

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

    public Map<Integer, Location> origins() {
        return origins;
    }

    public void setOrigin(int gridIndex, Location containerBlock) {
        if (containerBlock == null) {
            origins.remove(gridIndex);
        } else {
            origins.put(gridIndex, containerBlock.clone());
        }
    }
}
