package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.config.CraftBridgeConfig;
import com.dierks.craftbridge.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The full-block head look: one persistent {@link ItemDisplay} per workbench, holding
 * the textured head, scaled to cover the crafting table underneath.
 *
 * <p>Every display carries the PDC tag {@code craftbridge:linked_display} = the table
 * key, and the table record stores the display UUID, so each side can find the other.
 * {@link #sweep()} (startup) and {@link #sweepChunk(Chunk)} (when a chunk's entities
 * load) remove orphans whose table is gone and respawn displays that went missing —
 * that is what makes a WorldEdit/Towny regen wiping the table self-heal.
 */
public final class DisplayManager {

    public static final NamespacedKey LINKED_DISPLAY = Keys.key("linked_display");

    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private final WorkbenchItems items;

    public DisplayManager(CraftBridgePlugin plugin, WorkbenchStore store, WorkbenchItems items) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
    }

    /** Spawn (or replace) the display for a record and return the updated record. */
    public WorkbenchRecord spawn(WorkbenchRecord record) {
        World world = record.bukkitWorld();
        if (world == null) {
            return record;
        }
        remove(record.display());
        CraftBridgeConfig.WorkbenchDisplay cfg = plugin.config().workbenchDisplay();
        Location at = new Location(world, record.x() + cfg.offsetX(), record.y() + cfg.offsetY(), record.z() + cfg.offsetZ(),
                record.yaw() + cfg.yawOffset(), 0f);
        ItemDisplay display = world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(items.texturedHead());
            d.setItemDisplayTransform(cfg.transform());
            d.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new AxisAngle4f(),
                    new Vector3f(cfg.scale(), cfg.scale(), cfg.scale()), new AxisAngle4f()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(true);
            d.setInvulnerable(true);
            d.setSilent(true);
            d.setGravity(false);
            d.getPersistentDataContainer().set(LINKED_DISPLAY, PersistentDataType.STRING, record.key());
        });
        return record.withDisplay(display.getUniqueId());
    }

    /** Remove a display by UUID if it is loaded; unloaded orphans are caught by the chunk sweep. */
    public void remove(UUID display) {
        if (display == null) {
            return;
        }
        Entity e = Bukkit.getEntity(display);
        if (e instanceof ItemDisplay) {
            e.remove();
        }
    }

    public static boolean isOurs(Entity entity) {
        return entity instanceof ItemDisplay
                && entity.getPersistentDataContainer().has(LINKED_DISPLAY, PersistentDataType.STRING);
    }

    /** Startup sweep over every loaded chunk. Returns "removed orphans / respawned" counts. */
    public int[] sweep() {
        int removed = 0;
        int respawned = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (isOurs(display) && isOrphan(display)) {
                    display.remove();
                    removed++;
                }
            }
        }
        for (WorkbenchRecord record : new ArrayList<>(store.all())) {
            if (record.chunkLoaded()) {
                respawned += heal(record) ? 1 : 0;
            }
        }
        return new int[]{removed, respawned};
    }

    /** Same as {@link #sweep()} but for one chunk whose entities just loaded. */
    public void sweepChunk(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (isOurs(e) && isOrphan(e)) {
                e.remove();
            }
        }
        for (WorkbenchRecord record : new ArrayList<>(store.all())) {
            if (record.world().equals(chunk.getWorld().getName())
                    && (record.x() >> 4) == chunk.getX() && (record.z() >> 4) == chunk.getZ()) {
                heal(record);
            }
        }
    }

    /** Respawn every loaded display (after the config was tuned). */
    public int refreshAll() {
        int n = 0;
        for (WorkbenchRecord record : new ArrayList<>(store.all())) {
            if (record.chunkLoaded()) {
                store.put(spawn(record));
                n++;
            }
        }
        return n;
    }

    private boolean isOrphan(Entity display) {
        String key = display.getPersistentDataContainer().get(LINKED_DISPLAY, PersistentDataType.STRING);
        WorkbenchRecord record = key == null ? null : store.byKey(key);
        if (record == null) {
            return true;
        }
        Block block = record.block();
        if (block == null || block.getType() != Material.CRAFTING_TABLE) {
            return true;
        }
        // A second display for the same table (e.g. after a crash mid-write) is an orphan too.
        return record.display() != null && !record.display().equals(display.getUniqueId());
    }

    /**
     * Make one loaded record consistent: table gone → drop the record and its display;
     * display gone → respawn. Returns true if something was respawned.
     */
    private boolean heal(WorkbenchRecord record) {
        Block block = record.block();
        if (block == null) {
            return false;
        }
        if (block.getType() != Material.CRAFTING_TABLE) {
            plugin.getLogger().info("Linked Workbench at " + record.key() + " is no longer a crafting table; forgetting it.");
            remove(record.display());
            store.remove(record.key());
            return false;
        }
        Entity e = record.display() == null ? null : Bukkit.getEntity(record.display());
        if (e instanceof ItemDisplay && e.isValid()) {
            return false;
        }
        store.put(spawn(record));
        return true;
    }

    public List<WorkbenchRecord> records() {
        return new ArrayList<>(store.all());
    }
}
