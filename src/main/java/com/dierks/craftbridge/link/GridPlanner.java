package com.dierks.craftbridge.link;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what to put in each crafting-grid slot for a recipe the client asked for, given
 * what the player is carrying and what is in nearby storage.
 *
 * <p>This is the half of a CraftBridge transfer that the server does not take on trust. The
 * client sends the name of a recipe and nothing else — never what it has, never how much — so
 * the counts here come from the server's own reading of the player's inventory and the
 * containers in range. A client that lies about a recipe gets a recipe it could have asked
 * for by clicking; a client that lies about its inventory has nothing to lie with.
 *
 * <p>Bukkit-free on purpose: item types are indices into a table the caller owns, so every
 * rule below is a unit test rather than something only reproducible in-game.
 */
public final class GridPlanner {

    /** A grid slot the recipe needs filled, and the item types (by index) that would satisfy it. */
    public record Slot(int gridIndex, int[] options) {
    }

    /** How many of one item type are to hand, and where they are. */
    public record Supply(int fromPlayer, int fromStorage) {
        public int total() {
            return fromPlayer + fromStorage;
        }
    }

    /** What goes in one slot: which item type, and how many come from each place. */
    public record Fill(int gridIndex, int type, int fromPlayer, int fromStorage) {
        public int count() {
            return fromPlayer + fromStorage;
        }
    }

    /**
     * @param sets    how many complete sets the grid will hold
     * @param missing grid slots nothing could satisfy (empty when {@link #ok()})
     */
    public record Plan(List<Fill> fills, int sets, List<Integer> missing, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private GridPlanner() {
    }

    /**
     * @param slots       the recipe's non-empty slots, in grid order
     * @param supply      per item type, how many are available
     * @param maxStack    per item type, how many fit in one slot
     * @param maxTransfer true for as many sets as the ingredients allow, false for exactly one
     */
    public static Plan plan(List<Slot> slots, List<Supply> supply, int[] maxStack, boolean maxTransfer) {
        if (slots.isEmpty()) {
            return new Plan(List.of(), 0, List.of(), "that recipe has no ingredients");
        }

        // One item type per slot, decided by a single pass: a slot takes the first type it
        // accepts that still has something left for it. Deciding once and for all is what
        // keeps a slot holding one stack rather than a mixture across sets.
        int[] chosen = new int[slots.size()];
        int[] usedOnce = new int[supply.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            chosen[i] = -1;
            for (int type : slot.options()) {
                if (type < 0 || type >= supply.size()) {
                    continue;
                }
                if (supply.get(type).total() >= usedOnce[type] + 1) {
                    chosen[i] = type;
                    usedOnce[type]++;
                    break;
                }
            }
            if (chosen[i] < 0) {
                missing.add(slot.gridIndex());
            }
        }
        if (!missing.isEmpty()) {
            return new Plan(List.of(), 0, List.copyOf(missing),
                    missing.size() == 1 ? "one ingredient is not in range" : missing.size() + " ingredients are not in range");
        }

        // How many sets the scarcest ingredient allows, and how many one slot can hold.
        int sets = Integer.MAX_VALUE;
        for (int type = 0; type < supply.size(); type++) {
            if (usedOnce[type] > 0) {
                sets = Math.min(sets, supply.get(type).total() / usedOnce[type]);
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            sets = Math.min(sets, Math.max(1, maxStack[chosen[i]]));
        }
        if (!maxTransfer) {
            sets = 1;
        }
        if (sets <= 0) {
            return new Plan(List.of(), 0, List.of(), "not enough of one ingredient for a single set");
        }

        // Spend the player's own items before touching storage: what stays in a chest stays
        // findable, and only what came out of one has to be put back when the session ends.
        int[] player = new int[supply.size()];
        int[] storage = new int[supply.size()];
        for (int type = 0; type < supply.size(); type++) {
            player[type] = supply.get(type).fromPlayer();
            storage[type] = supply.get(type).fromStorage();
        }
        List<Fill> fills = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            int type = chosen[i];
            int fromPlayer = Math.min(sets, player[type]);
            player[type] -= fromPlayer;
            int fromStorage = Math.min(sets - fromPlayer, storage[type]);
            storage[type] -= fromStorage;
            fills.add(new Fill(slots.get(i).gridIndex(), type, fromPlayer, fromStorage));
        }
        return new Plan(List.copyOf(fills), sets, List.of(), null);
    }
}
