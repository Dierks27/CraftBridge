package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakePlannerTest {

    @Test
    void anOrdinaryContainerGivesEverything() {
        assertEquals(70, TakePlanner.takeable(new int[]{64, 0, 5, 1}, false));
    }

    @Test
    void aGolemChestKeepsOnePerSlot() {
        assertEquals(63 + 4, TakePlanner.takeable(new int[]{64, 0, 5, 1}, true));
    }

    @Test
    void singlesInAGolemChestAreNotTakeableAtAll() {
        assertEquals(0, TakePlanner.takeable(new int[]{1, 1, 1}, true));
        assertArrayEquals(new int[]{0, 0, 0}, TakePlanner.plan(new int[]{1, 1, 1}, true, 10));
    }

    @Test
    void negativeAndEmptySlotsCountForNothing() {
        assertEquals(0, TakePlanner.takeable(-3, true));
        assertEquals(0, TakePlanner.takeable(-3, false));
        assertEquals(0, TakePlanner.takeable(0, false));
    }

    @Test
    void anOrdinaryPullDrainsInSlotOrder() {
        assertArrayEquals(new int[]{5, 0, 5, 0}, TakePlanner.plan(new int[]{5, 0, 20, 30}, false, 10));
    }

    @Test
    void aGolemPullTakesFromTheFullestSlotFirst() {
        // 30 is fullest: 10 from it alone, the others untouched.
        assertArrayEquals(new int[]{0, 0, 0, 10}, TakePlanner.plan(new int[]{5, 0, 20, 30}, true, 10));
    }

    @Test
    void aGolemPullSpillsOverInOrderOfFullnessAndStopsAtOne() {
        // 30 gives 29, then 20 gives 19, then 5 gives 2 of its 4.
        assertArrayEquals(new int[]{2, 0, 19, 29}, TakePlanner.plan(new int[]{5, 0, 20, 30}, true, 50));
    }

    @Test
    void aGolemPullAskingForEverythingLeavesExactlyOneInEverySlot() {
        int[] amounts = {5, 0, 20, 30, 1};
        int[] take = TakePlanner.plan(amounts, true, Integer.MAX_VALUE);
        for (int i = 0; i < amounts.length; i++) {
            int after = amounts[i] - take[i];
            assertEquals(amounts[i] > 0 ? 1 : amounts[i], after, "slot " + i);
        }
    }

    @Test
    void equalSlotsAreTakenInSlotOrder() {
        assertArrayEquals(new int[]{9, 1, 0}, TakePlanner.plan(new int[]{10, 10, 10}, true, 10));
    }

    @Test
    void aPlanNeverExceedsWhatWasAskedOrWhatIsThere() {
        int[][] cases = {{64, 64, 3}, {1, 2, 3, 4}, {}, {0, 0}, {7}};
        for (int[] amounts : cases) {
            for (boolean keepOne : new boolean[]{false, true}) {
                for (int wanted : new int[]{0, 1, 5, 100, Integer.MAX_VALUE}) {
                    int[] take = TakePlanner.plan(amounts, keepOne, wanted);
                    int sum = 0;
                    for (int i = 0; i < take.length; i++) {
                        assertTrue(take[i] >= 0 && take[i] <= TakePlanner.takeable(amounts[i], keepOne));
                        sum += take[i];
                    }
                    assertEquals(Math.min(wanted, TakePlanner.takeable(amounts, keepOne)), sum);
                }
            }
        }
    }

    @Test
    void theTotalSaturates() {
        assertEquals(Integer.MAX_VALUE, TakePlanner.takeable(new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}, false));
    }

    @Test
    void nothingWantedMeansNothingTaken() {
        assertArrayEquals(new int[]{0, 0}, TakePlanner.plan(new int[]{5, 5}, false, 0));
        assertArrayEquals(new int[]{0, 0}, TakePlanner.plan(new int[]{5, 5}, true, -1));
    }
}
