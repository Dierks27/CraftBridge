package com.dierks.craftbridge.workbench;

import org.bukkit.block.Block;

/**
 * Identifies a block position as a plain string, {@code world:x:y:z}.
 *
 * <p>Used wherever CraftBridge has to answer "is this the same block?" across sources that
 * built their coordinates differently — a tracked record versus a block found by a scan.
 * Comparing {@link org.bukkit.Location} objects for that is fragile: equality folds in the
 * world reference, and yaw and pitch, none of which mean anything for a block, and a single
 * mismatch silently turns "skip this container" into "use this container". A string key has
 * one definition and cannot drift.
 */
public final class BlockKeys {

    private BlockKeys() {
    }

    public static String of(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static String of(Block block) {
        return of(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
