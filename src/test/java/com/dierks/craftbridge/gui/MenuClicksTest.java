package com.dierks.craftbridge.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dierks.craftbridge.gui.MenuClicks.Action;
import static com.dierks.craftbridge.gui.MenuClicks.Drag;
import static com.dierks.craftbridge.gui.MenuClicks.Region;
import static com.dierks.craftbridge.gui.MenuClicks.Traits;
import static com.dierks.craftbridge.gui.MenuClicks.decide;
import static com.dierks.craftbridge.gui.MenuClicks.decideDrag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Every click type against every region, with and without something on the cursor. The Combo
 * Chest shipped unable to pick an item up onto the cursor at all, because clicks in the
 * player's own inventory were cancelled wholesale; these are the cells that says so out loud.
 */
class MenuClicksTest {

    /** Every ClickType Bukkit can deliver, plus a name from the future. */
    private static final List<String> ALL_CLICKS = List.of(
            "LEFT", "SHIFT_LEFT", "RIGHT", "SHIFT_RIGHT", "WINDOW_BORDER_LEFT", "WINDOW_BORDER_RIGHT",
            "MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK", "DROP", "CONTROL_DROP", "CREATIVE",
            "SWAP_OFFHAND", "UNKNOWN", "SOME_NEW_CLICK_TYPE");

    private static final Traits DEPOSIT_MENU = new Traits(true, false);
    private static final Traits PLAIN_MENU = new Traits(false, false);
    private static final Traits PICKER_SLOT = new Traits(false, true);

    // ---- the player's own inventory ---------------------------------------------------

    @Test
    void pickingAnItemUpInYourOwnInventoryIsNeverRefused() {
        // The v0.8 regression: with the Combo Chest open, no click of any kind could get an
        // item onto the cursor, so no cursor deposit or drag deposit could ever begin.
        for (String click : ALL_CLICKS) {
            if (click.startsWith("SHIFT_") || click.equals("DOUBLE_CLICK")) {
                continue; // the two enumerated exceptions, asserted below
            }
            assertEquals(Action.VANILLA, decide(Region.BOTTOM, click, true, false, DEPOSIT_MENU),
                    click + " on a full slot with an empty cursor");
            assertEquals(Action.VANILLA, decide(Region.BOTTOM, click, false, true, DEPOSIT_MENU),
                    click + " on an empty slot holding an item");
            assertEquals(Action.VANILLA, decide(Region.BOTTOM, click, true, false, PLAIN_MENU),
                    click + " with a menu that takes no deposits");
        }
    }

    @Test
    void shiftClickingOutOfYourInventoryDepositsWhenTheMenuTakesDeposits() {
        assertEquals(Action.DEPOSIT_SLOT, decide(Region.BOTTOM, "SHIFT_LEFT", true, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_SLOT, decide(Region.BOTTOM, "SHIFT_RIGHT", true, false, DEPOSIT_MENU));
        // Nothing in the slot to deposit, and nowhere to put it in a menu that takes none.
        assertEquals(Action.CANCEL, decide(Region.BOTTOM, "SHIFT_LEFT", true, true, DEPOSIT_MENU));
        assertEquals(Action.CANCEL, decide(Region.BOTTOM, "SHIFT_LEFT", true, false, PLAIN_MENU));
    }

    @Test
    void doubleClickCollectIsRefusedBecauseItWouldMintTheMenusIcons() {
        assertEquals(Action.CANCEL, decide(Region.BOTTOM, "DOUBLE_CLICK", false, false, DEPOSIT_MENU));
        assertEquals(Action.CANCEL, decide(Region.BOTTOM, "DOUBLE_CLICK", false, false, PLAIN_MENU));
    }

    @Test
    void droppingOutsideTheWindowIsAlwaysTheirOwnBusiness() {
        for (String click : ALL_CLICKS) {
            assertEquals(Action.VANILLA, decide(Region.OUTSIDE, click, false, true, DEPOSIT_MENU), click);
            assertEquals(Action.VANILLA, decide(Region.OUTSIDE, click, false, true, PLAIN_MENU), click);
        }
    }

    // ---- menu slots -------------------------------------------------------------------

    @Test
    void clickingTheMenuWithAnItemInHandDepositsIt() {
        assertEquals(Action.DEPOSIT_ALL, decide(Region.TOP_BUTTON, "LEFT", false, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_ALL, decide(Region.TOP_BUTTON, "SHIFT_LEFT", false, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_ALL, decide(Region.TOP_BUTTON, "WINDOW_BORDER_LEFT", false, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_ONE, decide(Region.TOP_BUTTON, "RIGHT", false, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_ONE, decide(Region.TOP_BUTTON, "SHIFT_RIGHT", false, false, DEPOSIT_MENU));
        assertEquals(Action.DEPOSIT_ONE, decide(Region.TOP_BUTTON, "WINDOW_BORDER_RIGHT", false, false, DEPOSIT_MENU));
    }

    @Test
    void everyOtherGestureWithAFullCursorIsRefusedRatherThanGuessedAt() {
        for (String click : List.of("MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK", "DROP", "CONTROL_DROP",
                "CREATIVE", "SWAP_OFFHAND", "UNKNOWN", "SOME_NEW_CLICK_TYPE")) {
            assertEquals(Action.CANCEL, decide(Region.TOP_BUTTON, click, false, false, DEPOSIT_MENU), click);
        }
    }

    @Test
    void anEmptyCursorOnAMenuSlotIsAButtonPress() {
        for (String click : ALL_CLICKS) {
            assertEquals(Action.BUTTON, decide(Region.TOP_BUTTON, click, true, false, DEPOSIT_MENU), click);
            assertEquals(Action.BUTTON, decide(Region.TOP_BUTTON, click, true, false, PLAIN_MENU), click);
        }
    }

    @Test
    void aMenuThatTakesNoDepositsStillJustRunsItsButton() {
        // The recipe editor: an item in hand over a button is not a deposit anywhere.
        for (String click : ALL_CLICKS) {
            assertEquals(Action.BUTTON, decide(Region.TOP_BUTTON, click, false, false, PLAIN_MENU), click);
        }
    }

    // ---- editable slots (the recipe editor) -------------------------------------------

    @Test
    void editableSlotsKeepVanillaBehaviourExceptForBulkMoves() {
        for (String click : ALL_CLICKS) {
            Action expected = click.startsWith("SHIFT_") || click.equals("DOUBLE_CLICK")
                    ? Action.CANCEL : Action.EDITABLE;
            assertEquals(expected, decide(Region.TOP_EDITABLE, click, false, true, PLAIN_MENU), click);
        }
    }

    @Test
    void anEmptyHandOnAnEmptyEditableSlotOpensThePicker() {
        assertEquals(Action.PICKER, decide(Region.TOP_EDITABLE, "LEFT", true, true, PICKER_SLOT));
        // Only when there is a handler behind it, and only when there is nothing to move.
        assertEquals(Action.EDITABLE, decide(Region.TOP_EDITABLE, "LEFT", true, true, PLAIN_MENU));
        assertEquals(Action.EDITABLE, decide(Region.TOP_EDITABLE, "LEFT", true, false, PICKER_SLOT));
        assertEquals(Action.EDITABLE, decide(Region.TOP_EDITABLE, "LEFT", false, true, PICKER_SLOT));
    }

    @Test
    void placingIntoAnEditableSlotIsNeverRefused() {
        for (String click : ALL_CLICKS) {
            if (click.startsWith("SHIFT_") || click.equals("DOUBLE_CLICK")) {
                continue;
            }
            assertNotEquals(Action.CANCEL, decide(Region.TOP_EDITABLE, click, false, true, PICKER_SLOT), click);
        }
    }

    // ---- drags ------------------------------------------------------------------------

    @Test
    void aDragThatStaysInYourInventoryIsLeftAlone() {
        assertEquals(Drag.ALLOW, decideDrag(false, false, true));
        assertEquals(Drag.ALLOW, decideDrag(false, false, false));
    }

    @Test
    void aDragOverADepositMenuDeposits() {
        assertEquals(Drag.DEPOSIT, decideDrag(true, true, true));
        assertEquals(Drag.DEPOSIT, decideDrag(true, false, true));
    }

    @Test
    void aDragIntoAMenuThatTakesNoDepositsIsRefusedOnlyWhereTheMenuOwnsTheSlots() {
        assertEquals(Drag.CANCEL, decideDrag(true, true, false));
        assertEquals(Drag.ALLOW, decideDrag(true, false, false)); // all editable: the editor's input slots
    }

    @Test
    void aDragSpanningBothInventoriesDepositsOnlyItsShare() {
        // 9 items spread over three slots, two of them in the menu.
        int aimed = MenuClicks.dragIntoMenu(Map.of(3, 3, 7, 3, 60, 3), 54);
        assertEquals(6, aimed);
    }

    // ---- what stays on the cursor -----------------------------------------------------

    @Test
    void aRefusedDepositLeavesTheCursorExactlyAsItWas() {
        assertEquals(64, MenuClicks.keptOnCursor(64, 1, 1), "one offered, one refused");
        assertEquals(64, MenuClicks.keptOnCursor(64, 64, 64), "whole stack refused");
    }

    @Test
    void anAcceptedDepositTakesExactlyWhatWasAccepted() {
        assertEquals(63, MenuClicks.keptOnCursor(64, 1, 0), "right-click deposits one");
        assertEquals(0, MenuClicks.keptOnCursor(64, 64, 0), "left-click deposits the stack");
        assertEquals(34, MenuClicks.keptOnCursor(64, 40, 10), "40 offered, 10 came back");
        assertEquals(44, MenuClicks.keptOnCursor(64, 20, 0), "a drag over part of the menu");
    }

    @Test
    void nonsenseNumbersCanNeitherMintNorLoseItems() {
        assertEquals(64, MenuClicks.keptOnCursor(64, 0, 0));
        assertEquals(0, MenuClicks.keptOnCursor(64, 100, 0), "cannot deposit more than is held");
        assertEquals(64, MenuClicks.keptOnCursor(64, 10, 99), "cannot get back more than was offered");
        assertEquals(0, MenuClicks.keptOnCursor(0, 5, 0));
    }

    @Test
    void slotsThatGainNothingDoNotCountTowardsTheDeposit() {
        assertEquals(0, MenuClicks.dragIntoMenu(Map.of(3, 0, 60, 5), 54));
        assertEquals(4, MenuClicks.dragIntoMenu(Map.of(3, 4, 4, -1), 54));
    }
}
