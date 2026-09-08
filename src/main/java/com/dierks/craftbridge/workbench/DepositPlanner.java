package com.dierks.craftbridge.workbench;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where a deposited stack goes, in one fixed order. Kept free of Bukkit types so
 * the ordering can be unit tested:
 *
 * <ol>
 *   <li>top up partial stacks of the same item, nearest container first, up to the stack size;</li>
 *   <li>then an empty slot in a container that already holds that item, nearest first;</li>
 *   <li>then an empty slot in the nearest container with space.</li>
 * </ol>
 *
 * Whatever does not fit is reported as {@link Plan#leftover()} and stays with the player —
 * a deposit never destroys part of a stack. Containers the player may not use are filtered
 * out by the caller before planning, so every container here is a legal target.
 */
public final class DepositPlanner {

    /** Slot marker: the slot holds a different item, so it can never take this deposit. */
    public static final int OTHER = -1;
    /** Slot marker: the slot is empty. */
    public static final int EMPTY = 0;

    private DepositPlanner() {
    }

    /**
     * One target container.
     *
     * @param slots    one entry per slot: {@link #OTHER}, {@link #EMPTY}, or the amount of a
     *                 stack of the item being deposited
     * @param maxStack the most this item may stack to in this container
     */
    public record Container(int[] slots, int maxStack) {
    }

    /** Put {@code amount} items into slot {@code slot} of container {@code container}. */
    public record Move(int container, int slot, int amount) {
    }

    /** The moves to apply in order, and how many items did not fit anywhere. */
    public record Plan(List<Move> moves, int leftover) {
    }

    /**
     * @param containers candidate containers, nearest first
     * @param amount     how many items to store
     */
    public static Plan plan(List<Container> containers, int amount) {
        List<Move> moves = new ArrayList<>();
        int left = Math.max(0, amount);
        if (left == 0) {
            return new Plan(moves, 0);
        }

        // Work on copies so the caller's arrays are untouched, and remember which containers
        // already held the item before we put anything anywhere (that decides step 2 vs 3).
        int n = containers.size();
        int[][] slots = new int[n][];
        boolean[] alreadyHolds = new boolean[n];
        for (int c = 0; c < n; c++) {
            int[] src = containers.get(c).slots();
            slots[c] = src.clone();
            for (int held : src) {
                if (held > 0) {
                    alreadyHolds[c] = true;
                    break;
                }
            }
        }

        // 1. Top up partial stacks everywhere, nearest container first.
        for (int c = 0; c < n && left > 0; c++) {
            int max = containers.get(c).maxStack();
            for (int s = 0; s < slots[c].length && left > 0; s++) {
                int held = slots[c][s];
                if (held <= 0 || held >= max) {
                    continue;
                }
                int put = Math.min(left, max - held);
                moves.add(new Move(c, s, put));
                slots[c][s] = held + put;
                left -= put;
            }
        }

        // 2. Empty slots in containers that already held the item, then 3. anywhere else.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int c = 0; c < n && left > 0; c++) {
                if (pass == 0 ? !alreadyHolds[c] : alreadyHolds[c]) {
                    continue;
                }
                int max = containers.get(c).maxStack();
                for (int s = 0; s < slots[c].length && left > 0; s++) {
                    if (slots[c][s] != EMPTY) {
                        continue;
                    }
                    int put = Math.min(left, max);
                    moves.add(new Move(c, s, put));
                    slots[c][s] = put;
                    left -= put;
                }
            }
        }

        return new Plan(moves, left);
    }
}
