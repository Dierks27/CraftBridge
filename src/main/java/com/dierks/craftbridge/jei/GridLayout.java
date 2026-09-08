package com.dierks.craftbridge.jei;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Raw-slot layout of the two vanilla crafting menus JEI can transfer into. These are
 * the container-menu indexes the JEI client sends and Bukkit's {@code InventoryView}
 * raw slots — the same numbering.
 *
 * <ul>
 *   <li>Crafting table ({@code WORKBENCH}): 0 result, 1-9 grid, 10-36 main inventory, 37-45 hotbar.</li>
 *   <li>Player 2x2 ({@code CRAFTING}): 0 result, 1-4 grid, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand.</li>
 * </ul>
 */
public record GridLayout(String name, int gridStart, int gridSize, int inventoryStart, int inventorySize) {

    public static final GridLayout WORKBENCH = new GridLayout("crafting table", 1, 9, 10, 36);
    public static final GridLayout PLAYER = new GridLayout("player 2x2 grid", 1, 4, 9, 36);

    /**
     * The layout for an open Bukkit view type name ({@code WORKBENCH} / {@code CRAFTING}),
     * or null if JEI cannot transfer into it. Takes the enum name so this class stays
     * Bukkit-free and unit-testable.
     */
    public static GridLayout of(String inventoryTypeName) {
        if ("WORKBENCH".equals(inventoryTypeName)) {
            return WORKBENCH;
        }
        if ("CRAFTING".equals(inventoryTypeName)) {
            return PLAYER;
        }
        return null;
    }

    public Set<Integer> gridSlots() {
        return range(gridStart, gridSize);
    }

    public Set<Integer> inventorySlots() {
        return range(inventoryStart, inventorySize);
    }

    /** Grid + inventory raw slots, in order. */
    public Set<Integer> allSlots() {
        Set<Integer> all = new LinkedHashSet<>(gridSlots());
        all.addAll(inventorySlots());
        return all;
    }

    public boolean isGridSlot(int raw) {
        return raw >= gridStart && raw < gridStart + gridSize;
    }

    /** 0-based crafting-matrix index of a raw grid slot. */
    public int gridIndex(int raw) {
        return raw - gridStart;
    }

    private static Set<Integer> range(int start, int size) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            out.add(start + i);
        }
        return out;
    }
}
