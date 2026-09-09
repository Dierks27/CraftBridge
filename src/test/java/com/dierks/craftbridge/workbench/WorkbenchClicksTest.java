package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.craftbridge.workbench.WorkbenchClicks.Action;
import static com.dierks.craftbridge.workbench.WorkbenchClicks.Slot;
import static com.dierks.craftbridge.workbench.WorkbenchClicks.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every click type against every kind of slot, with and without something on the cursor.
 * This subsystem broke twice by cancelling clicks it had no business cancelling, so the
 * decision is a table here rather than a chain of ifs in a listener.
 */
class WorkbenchClicksTest {

    /** Every ClickType Bukkit can deliver, plus a name from the future. */
    private static final List<String> ALL_CLICKS = List.of(
            "LEFT", "SHIFT_LEFT", "RIGHT", "SHIFT_RIGHT", "WINDOW_BORDER_LEFT", "WINDOW_BORDER_RIGHT",
            "MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK", "DROP", "CONTROL_DROP", "CREATIVE",
            "SWAP_OFFHAND", "UNKNOWN", "SOME_NEW_CLICK_TYPE");

    @Test
    void aRealSlotIsNeverInterferedWith() {
        for (String click : ALL_CLICKS) {
            assertEquals(Action.ALLOW, decide(Slot.REAL, true, click), click + " with an empty cursor");
            assertEquals(Action.ALLOW, decide(Slot.REAL, false, click), click + " holding an item");
        }
    }

    @Test
    void puttingAnItemIntoAPhantomSlotIsOrdinaryVanillaBehaviour() {
        // The regression that made it impossible to move your own items around: a phantom
        // slot is a genuinely empty slot, so placing into it must never be refused.
        for (String click : ALL_CLICKS) {
            assertEquals(Action.ALLOW, decide(Slot.PHANTOM, false, click), click + " holding an item");
            assertEquals(Action.ALLOW, decide(Slot.BUTTON, false, click), click + " holding an item");
        }
    }

    @Test
    void takingFromAPhantomPullsABoundedAmount() {
        assertEquals(Action.PULL_ONE, decide(Slot.PHANTOM, true, "LEFT"));
        assertEquals(Action.PULL_HALF, decide(Slot.PHANTOM, true, "RIGHT"));
        assertEquals(Action.PULL_ALL, decide(Slot.PHANTOM, true, "SHIFT_LEFT"));
        assertEquals(Action.PULL_ALL, decide(Slot.PHANTOM, true, "SHIFT_RIGHT"));
        assertEquals(Action.PULL_ONE, decide(Slot.PHANTOM, true, "WINDOW_BORDER_LEFT"));
        assertEquals(Action.PULL_HALF, decide(Slot.PHANTOM, true, "WINDOW_BORDER_RIGHT"));
    }

    @Test
    void everyOtherGestureOnAPhantomIsRefused() {
        for (String click : List.of("MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK", "DROP", "CONTROL_DROP",
                "CREATIVE", "SWAP_OFFHAND", "UNKNOWN", "SOME_NEW_CLICK_TYPE")) {
            assertEquals(Action.CANCEL, decide(Slot.PHANTOM, true, click), click);
        }
    }

    @Test
    void pageButtonsTurnThePageOnAPlainClickAndRefuseTheRest() {
        for (String click : List.of("LEFT", "RIGHT", "SHIFT_LEFT", "SHIFT_RIGHT")) {
            assertEquals(Action.PAGE, decide(Slot.BUTTON, true, click), click);
        }
        for (String click : List.of("MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK", "DROP", "SWAP_OFFHAND")) {
            assertEquals(Action.CANCEL, decide(Slot.BUTTON, true, click), click);
        }
    }

    @Test
    void anUnknownClickTypeNeverLeaksItemsOutOfAPhantom() {
        assertEquals(Action.CANCEL, decide(Slot.PHANTOM, true, null));
        assertEquals(Action.CANCEL, decide(Slot.PHANTOM, true, ""));
    }
}
