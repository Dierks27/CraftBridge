package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.sort.MiddleClickRules.Outcome;
import com.dierks.craftbridge.sort.MiddleClickRules.Screen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiddleClickRulesTest {

    @Test
    void overAChestsSlotsTheChestIsSorted() {
        assertEquals(Outcome.SORT_CONTAINER, MiddleClickRules.decide(Screen.SORTABLE_CONTAINER, false, true, true));
        assertEquals(Outcome.SORT_CONTAINER, MiddleClickRules.decide(Screen.SORTABLE_CONTAINER, false, true, false));
    }

    @Test
    void overTheirOwnSlotsOnlyThePlayersRowsAreSorted() {
        assertEquals(Outcome.SORT_PLAYER, MiddleClickRules.decide(Screen.SORTABLE_CONTAINER, true, true, true));
        assertEquals(Outcome.SORT_PLAYER, MiddleClickRules.decide(Screen.OWN_INVENTORY, true, true, true));
    }

    @Test
    void inventorySortingSwitchedOffSaysSo() {
        assertEquals(Outcome.INVENTORY_SORTING_OFF, MiddleClickRules.decide(Screen.OWN_INVENTORY, true, true, false));
        assertEquals(Outcome.INVENTORY_SORTING_OFF, MiddleClickRules.decide(Screen.SORTABLE_CONTAINER, true, true, false));
    }

    @Test
    void aCursorStackIsNeverASort() {
        for (Screen screen : Screen.values()) {
            for (boolean side : new boolean[]{false, true}) {
                assertEquals(Outcome.SILENT, MiddleClickRules.decide(screen, side, false, true), screen + " " + side);
            }
        }
    }

    @Test
    void menusWorkbenchesAndOtherScreensStaySilent() {
        for (Screen screen : new Screen[]{Screen.CRAFTBRIDGE_MENU, Screen.OTHER}) {
            for (boolean side : new boolean[]{false, true}) {
                assertEquals(Outcome.SILENT, MiddleClickRules.decide(screen, side, true, true), screen + " " + side);
            }
        }
    }

    @Test
    void theInventoryScreensUpperHalfIsNotAContainer() {
        // The 2x2 crafting grid and armour slots of the player's own screen: nothing to sort.
        assertEquals(Outcome.SILENT, MiddleClickRules.decide(Screen.OWN_INVENTORY, false, true, true));
    }
}
