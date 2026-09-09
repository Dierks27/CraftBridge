package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant that matters more than any single behaviour: <b>no path creates or destroys
 * items</b>. Storage plus inventory is counted before and after, and must match.
 *
 * <p>This models the moves the Linked Workbench makes — pulling from containers into the
 * player's slots, and pushing leftovers back — with the same bounds the real code uses, so
 * the arithmetic is exercised even though Bukkit inventories cannot be built in a unit test.
 */
class StorageAccountingTest {

    /** A player's inventory as slot amounts; -1 for a slot holding something else. */
    private static final class Sim {
        final int[] slots;
        final int maxStack;
        int storage;
        /** What the player is holding on the cursor: in limbo, but still theirs. */
        int cursor;
        /** What vanilla dropped at their feet when a session ended mid-hold. Still in the world. */
        int dropped;

        Sim(int storage, int maxStack, int... slots) {
            this.storage = storage;
            this.maxStack = maxStack;
            this.slots = slots.clone();
        }

        int free() {
            int n = 0;
            for (int s : slots) {
                if (s == 0) {
                    n++;
                }
            }
            return n;
        }

        int total() {
            int n = storage + cursor + dropped;
            for (int s : slots) {
                if (s > 0) {
                    n += s;
                }
            }
            return n;
        }

        /**
         * Left- or right-clicking a phantom: the stack goes straight onto the cursor, as if
         * taken out of a chest. A click with something already on the cursor is a place, not
         * a pull, so it moves nothing.
         */
        int pullToCursor(PullPlanner.Mode mode) {
            if (cursor != 0) {
                return 0;
            }
            int moved = Math.min(PullPlanner.amount(mode, storage, maxStack, 1), storage);
            storage -= moved;
            cursor += moved;
            return moved;
        }

        /**
         * The session ends while the player is holding something: closing, disconnecting,
         * dying, teleporting or walking out of range. Paper drops the carried item at their
         * feet rather than deleting it (its own "Drop carried item when player has
         * disconnected" patch, and vanilla's container-close handling), so it stays counted.
         */
        void endSessionMidHold() {
            dropped += cursor;
            cursor = 0;
        }

        /** Where the next phantom sits: phantoms only ever occupy empty slots. */
        int firstFree() {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == 0) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Pull into the clicked slot first, then any other free slot; overflow goes back.
         * A pull into a slot that is not empty does nothing, exactly as
         * {@code PhantomManager#pull} refuses when something real landed there.
         */
        int pull(int clicked, PullPlanner.Mode mode) {
            if (clicked < 0 || slots[clicked] != 0) {
                return 0;
            }
            int want = PullPlanner.amount(mode, storage, maxStack, free());
            int moved = Math.min(want, storage);
            storage -= moved;
            int left = moved;
            int into = Math.min(left, maxStack);
            slots[clicked] += into;
            left -= into;
            for (int i = 0; i < slots.length && left > 0; i++) {
                if (slots[i] != 0) {
                    continue;
                }
                int put = Math.min(left, maxStack);
                slots[i] = put;
                left -= put;
            }
            storage += left; // never dropped, never lost
            return moved - left;
        }
    }

    @Test
    void aSingleStackPullBalances() {
        Sim sim = new Sim(500, 64, 0, 0, 0, -1);
        int before = sim.total();
        sim.pull(0, PullPlanner.Mode.ONE);
        assertEquals(before, sim.total());
        assertEquals(64, sim.slots[0]);
        assertEquals(436, sim.storage);
    }

    @Test
    void aHalfStackPullBalances() {
        Sim sim = new Sim(10, 64, 0, 0);
        int before = sim.total();
        sim.pull(0, PullPlanner.Mode.HALF);
        assertEquals(before, sim.total());
        assertEquals(10, sim.slots[0]); // storage had less than half a stack
        assertEquals(0, sim.storage);
    }

    @Test
    void aBulkPullFillsEveryFreeSlotAndBalances() {
        Sim sim = new Sim(500, 64, 0, 0, 0, -1, 0);
        int before = sim.total();
        int moved = sim.pull(0, PullPlanner.Mode.ALL);
        assertEquals(before, sim.total());
        assertEquals(256, moved); // four free slots
        assertEquals(244, sim.storage);
        for (int i : new int[]{0, 1, 2, 4}) {
            assertEquals(64, sim.slots[i]);
        }
    }

    @Test
    void pullingIntoASlotThatIsNoLongerEmptyDoesNothing() {
        Sim sim = new Sim(500, 64, 5, 0);
        int before = sim.total();
        assertEquals(0, sim.pull(0, PullPlanner.Mode.ONE));
        assertEquals(before, sim.total());
        assertEquals(5, sim.slots[0]);
    }

    @Test
    void nothingMovesWhenThereIsNoRoom() {
        Sim sim = new Sim(500, 64, -1, -1);
        int before = sim.total();
        assertEquals(0, sim.pull(0, PullPlanner.Mode.ALL));
        assertEquals(before, sim.total());
        assertEquals(500, sim.storage);
    }

    @Test
    void aLeftClickPullPutsAStackOnTheCursorAndBalances() {
        Sim sim = new Sim(500, 64, 0, 0);
        int before = sim.total();
        assertEquals(64, sim.pullToCursor(PullPlanner.Mode.ONE));
        assertEquals(before, sim.total());
        assertEquals(64, sim.cursor);
        assertEquals(436, sim.storage);
        for (int slot : sim.slots) {
            assertEquals(0, slot, "a cursor pull must not touch the inventory slots");
        }
    }

    @Test
    void aRightClickPullPutsHalfAStackOnTheCursor() {
        Sim sim = new Sim(500, 64, 0);
        int before = sim.total();
        assertEquals(32, sim.pullToCursor(PullPlanner.Mode.HALF));
        assertEquals(before, sim.total());
        assertEquals(32, sim.cursor);
    }

    @Test
    void aPullWithSomethingAlreadyOnTheCursorMovesNothing() {
        // That click is a place, which vanilla handles; it must never also pull.
        Sim sim = new Sim(500, 64, 0);
        sim.cursor = 7;
        int before = sim.total();
        assertEquals(0, sim.pullToCursor(PullPlanner.Mode.ONE));
        assertEquals(before, sim.total());
        assertEquals(7, sim.cursor);
        assertEquals(500, sim.storage);
    }

    @Test
    void closingWhileHoldingAPulledStackLosesNothing() {
        Sim sim = new Sim(100, 64, 0, 0);
        int before = sim.total();
        sim.pullToCursor(PullPlanner.Mode.ONE);
        sim.endSessionMidHold();
        assertEquals(before, sim.total());
        assertEquals(0, sim.cursor);
        assertEquals(64, sim.dropped);
    }

    @Test
    void repeatedPullAndInterruptCyclesAlwaysBalance() {
        for (PullPlanner.Mode mode : new PullPlanner.Mode[]{PullPlanner.Mode.ONE, PullPlanner.Mode.HALF}) {
            for (int maxStack : new int[]{1, 16, 64}) {
                Sim sim = new Sim(137, maxStack, 0, 0, -1, 0);
                int before = sim.total();
                for (int round = 0; round < 12; round++) {
                    sim.pullToCursor(mode);
                    assertTrue(sim.cursor <= maxStack, "cursor over the stack size for " + mode);
                    sim.endSessionMidHold();
                    assertEquals(before, sim.total(), mode + " maxStack=" + maxStack + " round " + round);
                    assertTrue(sim.storage >= 0, "storage went negative for " + mode);
                }
            }
        }
    }

    @Test
    void nothingMovesWhenStorageIsEmpty() {
        Sim sim = new Sim(0, 64, 0, 0);
        int before = sim.total();
        assertEquals(0, sim.pull(0, PullPlanner.Mode.ONE));
        assertEquals(before, sim.total());
    }

    @Test
    void repeatedPullsOfEveryKindAlwaysBalance() {
        for (PullPlanner.Mode mode : PullPlanner.Mode.values()) {
            for (int maxStack : new int[]{1, 16, 64}) {
                Sim sim = new Sim(137, maxStack, 0, 0, 0, 0, -1, 0);
                int before = sim.total();
                for (int round = 0; round < 12; round++) {
                    // Each round clicks wherever the phantom has moved to: an empty slot.
                    sim.pull(sim.firstFree(), mode);
                    assertEquals(before, sim.total(), mode + " maxStack=" + maxStack + " round " + round);
                    for (int slot : sim.slots) {
                        assertTrue(slot <= maxStack, "slot over the stack size for " + mode);
                    }
                    assertTrue(sim.storage >= 0, "storage went negative for " + mode);
                }
            }
        }
    }
}
