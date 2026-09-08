package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.craftbridge.workbench.DepositPlanner.EMPTY;
import static com.dierks.craftbridge.workbench.DepositPlanner.OTHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deposit ordering: partial stacks first, then empties where the item already lives, then anywhere. */
class DepositPlannerTest {

    private static DepositPlanner.Container chest(int... slots) {
        return new DepositPlanner.Container(slots, 64);
    }

    /** Apply a plan to a copy of the containers so the result can be asserted directly. */
    private static int[][] apply(List<DepositPlanner.Container> containers, DepositPlanner.Plan plan) {
        int[][] out = new int[containers.size()][];
        for (int i = 0; i < containers.size(); i++) {
            out[i] = containers.get(i).slots().clone();
        }
        for (DepositPlanner.Move move : plan.moves()) {
            int held = out[move.container()][move.slot()];
            out[move.container()][move.slot()] = (held == EMPTY ? 0 : held) + move.amount();
        }
        return out;
    }

    @Test
    void topsUpAPartialStackRatherThanUsingAnEmptySlot() {
        // Jeff's case: a chest with 10 cobble and free slots, deposit 5 -> one stack of 15.
        List<DepositPlanner.Container> chests = List.of(chest(10, EMPTY, EMPTY));
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 5);
        assertEquals(0, plan.leftover());
        assertEquals(List.of(new DepositPlanner.Move(0, 0, 5)), plan.moves());
        assertArrayEquals2(new int[]{15, 0, 0}, apply(chests, plan)[0]);
    }

    @Test
    void fillsPartialsInEveryContainerBeforeAnyEmptySlot() {
        List<DepositPlanner.Container> chests = List.of(
                chest(60, EMPTY, EMPTY),   // nearest: 4 of room in its stack
                chest(50, OTHER));         // farther: 14 of room in its stack
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 18);
        assertEquals(0, plan.leftover());
        int[][] after = apply(chests, plan);
        assertArrayEquals2(new int[]{64, 0, 0}, after[0]);
        assertArrayEquals2(new int[]{64, OTHER}, after[1]);
    }

    @Test
    void prefersAnEmptySlotWhereTheItemAlreadyLives() {
        List<DepositPlanner.Container> chests = List.of(
                chest(EMPTY, EMPTY),       // nearest, but holds none of the item
                chest(64, EMPTY));         // farther, already holds it and has room
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 20);
        assertEquals(0, plan.leftover());
        int[][] after = apply(chests, plan);
        assertArrayEquals2(new int[]{EMPTY, EMPTY}, after[0]);
        assertArrayEquals2(new int[]{64, 20}, after[1]);
    }

    @Test
    void fallsBackToTheNearestContainerWithAFreeSlot() {
        List<DepositPlanner.Container> chests = List.of(
                chest(OTHER, EMPTY),       // nearest with space, holds none of the item
                chest(OTHER, OTHER));
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 7);
        assertEquals(0, plan.leftover());
        assertArrayEquals2(new int[]{OTHER, 7}, apply(chests, plan)[0]);
    }

    @Test
    void spillsOverManySlotsAndReportsWhatDoesNotFit() {
        List<DepositPlanner.Container> chests = List.of(chest(60, EMPTY, OTHER));
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 100);
        // 4 tops up the partial stack, 64 fills the empty slot, 32 has nowhere to go.
        assertEquals(32, plan.leftover());
        assertArrayEquals2(new int[]{64, 64, OTHER}, apply(chests, plan)[0]);
    }

    @Test
    void refusesEverythingWhenNothingHasRoom() {
        List<DepositPlanner.Container> chests = List.of(chest(64, OTHER), chest(OTHER, OTHER));
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 9);
        assertEquals(9, plan.leftover());
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void respectsASmallerStackSize() {
        List<DepositPlanner.Container> chests = List.of(new DepositPlanner.Container(new int[]{10, EMPTY}, 16));
        DepositPlanner.Plan plan = DepositPlanner.plan(chests, 20);
        assertEquals(0, plan.leftover());
        assertArrayEquals2(new int[]{16, 14}, apply(chests, plan)[0]);
    }

    @Test
    void leavesTheCallersArraysAlone() {
        int[] slots = {10, EMPTY};
        List<DepositPlanner.Container> chests = List.of(new DepositPlanner.Container(slots, 64));
        DepositPlanner.plan(chests, 5);
        assertArrayEquals2(new int[]{10, EMPTY}, slots);
    }

    private static void assertArrayEquals2(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
