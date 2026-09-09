package com.dierks.craftbridge.workbench;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Combo Chest's own barrel must never be storage. That reduces to "the key a scan computes
 * for the block equals the key the store recorded for it", so it is worth pinning down —
 * a single mismatch turns "skip this container" into "use this container", which is how a
 * deposit ended up inside the terminal it was made from.
 */
class BlockKeysTest {

    @Test
    void aRecordAndTheBlockItDescribesShareAKey() {
        String fromStore = BlockKeys.of("world", 128, 64, -320);
        String fromScan = BlockKeys.of("world", 128, 64, -320);
        assertEquals(fromStore, fromScan);
        assertTrue(Set.of(fromStore).contains(fromScan), "a scan must find the terminal in the store's key set");
    }

    @Test
    void negativeAndZeroCoordinatesRoundTrip() {
        assertEquals("world:-1:-64:0", BlockKeys.of("world", -1, -64, 0));
        assertEquals("world:0:0:0", BlockKeys.of("world", 0, 0, 0));
    }

    @Test
    void differentWorldsAreDifferentBlocks() {
        assertNotEquals(BlockKeys.of("world", 1, 2, 3), BlockKeys.of("world_nether", 1, 2, 3));
        assertFalse(Set.of(BlockKeys.of("world", 1, 2, 3)).contains(BlockKeys.of("world_nether", 1, 2, 3)));
    }

    @Test
    void neighbouringBlocksNeverCollide() {
        String origin = BlockKeys.of("world", 1, 2, 3);
        for (int[] d : new int[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0}, {0, 0, -1}}) {
            assertNotEquals(origin, BlockKeys.of("world", 1 + d[0], 2 + d[1], 3 + d[2]));
        }
        // The one that matters: 11,2,3 must not look like 1,12,3 through sloppy concatenation.
        assertNotEquals(BlockKeys.of("world", 11, 2, 3), BlockKeys.of("world", 1, 12, 3));
    }
}
