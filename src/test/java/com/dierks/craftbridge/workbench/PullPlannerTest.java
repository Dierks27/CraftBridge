package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import static com.dierks.craftbridge.workbench.PullPlanner.Mode;
import static com.dierks.craftbridge.workbench.PullPlanner.amount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pull is bounded by what storage holds and by the room the player has. Both, always. */
class PullPlannerTest {

    @Test
    void oneClickTakesAStack() {
        assertEquals(64, amount(Mode.ONE, 500, 64, 10));
        assertEquals(16, amount(Mode.ONE, 500, 16, 10)); // ender pearls
        assertEquals(1, amount(Mode.ONE, 500, 1, 10));   // unstackable
    }

    @Test
    void rightClickTakesHalfAStackRoundedUp() {
        assertEquals(32, amount(Mode.HALF, 500, 64, 10));
        assertEquals(8, amount(Mode.HALF, 500, 16, 10));
        assertEquals(1, amount(Mode.HALF, 500, 1, 10));
    }

    @Test
    void shiftClickFillsTheFreeSlotsAndNoMore() {
        assertEquals(192, amount(Mode.ALL, 500, 64, 3));
        assertEquals(500, amount(Mode.ALL, 500, 64, 100)); // storage runs out first
    }

    @Test
    void storageIsNeverOverdrawn() {
        for (Mode mode : Mode.values()) {
            assertEquals(5, amount(mode, 5, 64, 10), mode.name());
            assertEquals(0, amount(mode, 0, 64, 10), mode.name());
        }
    }

    @Test
    void withNoFreeSlotNothingIsPulled() {
        for (Mode mode : Mode.values()) {
            assertEquals(0, amount(mode, 500, 64, 0), mode.name());
        }
    }

    @Test
    void oneFreeSlotNeverTakesMoreThanItHolds() {
        for (Mode mode : Mode.values()) {
            assertTrue(amount(mode, 500, 64, 1) <= 64, mode.name());
        }
        assertEquals(64, amount(Mode.ALL, 500, 64, 1));
    }

    @Test
    void nonsenseInputsPullNothing() {
        assertEquals(0, amount(Mode.ONE, -5, 64, 10));
        assertEquals(0, amount(Mode.ONE, 500, 0, 10));
        assertEquals(0, amount(Mode.ONE, 500, 64, -1));
    }

    @Test
    void aHugeInventoryDoesNotOverflowTheBound() {
        assertTrue(amount(Mode.ALL, Integer.MAX_VALUE, 64, Integer.MAX_VALUE) > 0);
    }
}
