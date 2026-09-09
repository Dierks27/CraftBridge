package com.dierks.craftbridge.link;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deltas are only safe in order, and a view that has silently drifted would have JEI offer
 * crafts the server then refuses. So the gap cases matter as much as the happy path.
 */
class SnapshotTrackerTest {

    private static byte[] item(int id) {
        return new byte[]{(byte) id};
    }

    private static LinkProtocol.Storage full(int sequence, int... idsAndCounts) {
        return new LinkProtocol.Storage(sequence, true, entries(idsAndCounts));
    }

    private static LinkProtocol.Storage delta(int sequence, int... idsAndCounts) {
        return new LinkProtocol.Storage(sequence, false, entries(idsAndCounts));
    }

    private static List<LinkProtocol.Entry> entries(int... idsAndCounts) {
        List<LinkProtocol.Entry> out = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndCounts.length; i += 2) {
            out.add(new LinkProtocol.Entry(item(idsAndCounts[i]), idsAndCounts[i + 1]));
        }
        return out;
    }

    @Test
    void nothingIsUsableUntilAFullSnapshotArrives() {
        SnapshotTracker tracker = new SnapshotTracker();
        assertTrue(tracker.needsSnapshot());
        assertFalse(tracker.apply(delta(1, 1, 5)), "a delta with no snapshot behind it must be refused");
        assertTrue(tracker.needsSnapshot());
    }

    @Test
    void aSnapshotThenDeltasInOrderKeepsTheViewCurrent() {
        SnapshotTracker tracker = new SnapshotTracker();
        assertTrue(tracker.apply(full(4, 1, 100, 2, 7)));
        assertFalse(tracker.needsSnapshot());
        assertEquals(100, tracker.count(item(1)));

        assertTrue(tracker.apply(delta(5, 1, 90)));
        assertEquals(90, tracker.count(item(1)));
        assertEquals(7, tracker.count(item(2)), "untouched types stay as they were");
        assertEquals(5, tracker.sequence());
    }

    @Test
    void aZeroInADeltaRemovesTheType() {
        SnapshotTracker tracker = new SnapshotTracker();
        tracker.apply(full(1, 1, 10, 2, 4));
        tracker.apply(delta(2, 2, 0));
        assertEquals(0, tracker.count(item(2)));
        assertEquals(1, tracker.counts().size());
    }

    @Test
    void aGapStopsTheViewAndAsksForAFullSnapshot() {
        SnapshotTracker tracker = new SnapshotTracker();
        tracker.apply(full(1, 1, 10));
        assertFalse(tracker.apply(delta(3, 1, 999)), "sequence 2 never arrived");
        assertTrue(tracker.needsSnapshot());
        assertEquals(10, tracker.count(item(1)), "the stale delta must not have been applied");

        assertTrue(tracker.apply(full(3, 1, 999)));
        assertFalse(tracker.needsSnapshot());
        assertEquals(999, tracker.count(item(1)));
    }

    @Test
    void aRepeatedDeltaIsRefusedRatherThanAppliedTwice() {
        SnapshotTracker tracker = new SnapshotTracker();
        tracker.apply(full(1, 1, 10));
        assertTrue(tracker.apply(delta(2, 1, 9)));
        assertFalse(tracker.apply(delta(2, 1, 9)));
        assertTrue(tracker.needsSnapshot());
    }

    @Test
    void theServerSideDiffSendsOnlyWhatChanged() {
        SnapshotTracker sent = new SnapshotTracker();
        sent.apply(full(1, 1, 10, 2, 5, 3, 1));

        Map<SnapshotTracker.ItemKey, Integer> now = new LinkedHashMap<>();
        now.put(new SnapshotTracker.ItemKey(item(1)), 10);  // unchanged
        now.put(new SnapshotTracker.ItemKey(item(2)), 64);  // changed
        now.put(new SnapshotTracker.ItemKey(item(4)), 3);   // new
        // item 3 is gone

        List<LinkProtocol.Entry> changes = sent.diff(now);
        assertEquals(3, changes.size(), "one changed, one new, one removed - and not the unchanged one");
        Map<Integer, Integer> byId = new LinkedHashMap<>();
        for (LinkProtocol.Entry e : changes) {
            byId.put((int) e.item()[0], e.count());
        }
        assertEquals(64, byId.get(2));
        assertEquals(3, byId.get(4));
        assertEquals(0, byId.get(3), "a removed type is sent as zero");
        assertFalse(byId.containsKey(1));
    }

    @Test
    void aFullSnapshotAndItsDeltasSurviveARoundTripThroughTheWire() {
        SnapshotTracker server = new SnapshotTracker();
        SnapshotTracker client = new SnapshotTracker();

        Map<SnapshotTracker.ItemKey, Integer> state = new LinkedHashMap<>();
        state.put(new SnapshotTracker.ItemKey(item(1)), 64);
        state.put(new SnapshotTracker.ItemKey(item(2)), 12);
        int seq = server.advanceTo(state);
        assertTrue(client.apply(LinkProtocol.decodeStorage(LinkProtocol.encode(
                new LinkProtocol.Storage(seq, true, entries(1, 64, 2, 12))))));

        // A craft consumes some of type 1 and all of type 2.
        Map<SnapshotTracker.ItemKey, Integer> after = new LinkedHashMap<>();
        after.put(new SnapshotTracker.ItemKey(item(1)), 55);
        List<LinkProtocol.Entry> changes = server.diff(after);
        int next = server.advanceTo(after);
        assertTrue(client.apply(LinkProtocol.decodeStorage(LinkProtocol.encode(
                new LinkProtocol.Storage(next, false, changes)))));

        assertEquals(55, client.count(item(1)));
        assertEquals(0, client.count(item(2)));
        assertEquals(server.sequence(), client.sequence());
    }
}
