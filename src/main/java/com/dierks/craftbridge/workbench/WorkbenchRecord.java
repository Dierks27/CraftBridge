package com.dierks.craftbridge.workbench;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/** One placed CraftBridge block (Linked Workbench or Combo Chest): where the real block is and which display dresses it. */
public record WorkbenchRecord(BlockKind kind, String world, int x, int y, int z, UUID display, UUID owner, float yaw) {

    public static String keyOf(Block block) {
        return keyOf(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static String keyOf(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public String key() {
        return keyOf(world, x, y, z);
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** The block, or null if the world is not loaded. */
    public Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public Location location() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z);
    }

    public boolean chunkLoaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    public WorkbenchRecord withDisplay(UUID newDisplay) {
        return new WorkbenchRecord(kind, world, x, y, z, newDisplay, owner, yaw);
    }
}
