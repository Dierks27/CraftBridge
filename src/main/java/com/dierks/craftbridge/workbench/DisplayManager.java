package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.config.CraftBridgeConfig;
import com.dierks.craftbridge.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The full-block look: one persistent {@link ItemDisplay} per workbench, covering the real
 * crafting table or barrel underneath.
 *
 * <p>In {@code display.mode: model} it holds the block's item with CraftBridge's custom model
 * data and is hidden by default: {@link #showTo(Player)} shows it to each player whose client
 * loaded the resource pack (the {@link #viewers(Predicate)} rule, set by the resource-pack
 * feature), and everyone else sees the plain block. In {@code head} mode it holds the textured
 * head, scaled to cover the block, and everyone sees it.
 *
 * <p>Every display carries the PDC tag {@code craftbridge:linked_display} = the table
 * key, and the table record stores the display UUID, so each side can find the other.
 * {@link #sweep()} (startup) and {@link #sweepChunk(Chunk)} (when a chunk's entities
 * load) remove orphans whose table is gone and respawn displays that went missing —
 * that is what makes a WorldEdit/Towny regen wiping the table self-heal.
 */
public final class DisplayManager {

    public static final NamespacedKey LINKED_DISPLAY = Keys.key("linked_display");
    /**
     * What a display was spawned to look like (mode, item and geometry). A display whose look
     * no longer matches config.yml, e.g. after the upgrade that switched to mode: model, is
     * respawned by the sweeps.
     */
    public static final NamespacedKey DISPLAY_LOOK = Keys.key("display_look");

    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private final WorkbenchItems items;
    /** Who sees model displays; nobody until the resource-pack feature says otherwise. */
    private Predicate<Player> viewers = player -> false;

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
        BlockKind kind = record.kind();
        boolean model = plugin.config().displayMode(kind) == CraftBridgeConfig.DisplayMode.MODEL;
        String look = lookOf(kind);
        ItemStack item = items.displayItem(kind);
        ItemDisplay display;
        if (model) {
            // The entity sits on top of the block, so the light it is drawn with is the light
            // above the block, not the darkness inside it; the translation brings the model back
            // down over the block. The model's front is north, and yaw 180 turns north toward
            // the player who placed it.
            float scale = plugin.config().modelScale(kind);
            Location at = new Location(world, record.x() + 0.5, record.y() + 1.0, record.z() + 0.5,
                    record.yaw() + 180f, 0f);
            display = world.spawn(at, ItemDisplay.class, d -> {
                d.setVisibleByDefault(false);
                dress(d, record, item, look, ItemDisplay.ItemDisplayTransform.NONE,
                        new Vector3f(0f, -0.5f, 0f), scale);
            });
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (seesModels(player)) {
                    player.showEntity(plugin, display);
                }
            }
        } else {
            CraftBridgeConfig.WorkbenchDisplay cfg = plugin.config().displayFor(kind);
            Location at = new Location(world, record.x() + cfg.offsetX(), record.y() + cfg.offsetY(), record.z() + cfg.offsetZ(),
                    record.yaw() + cfg.yawOffset(), 0f);
            display = world.spawn(at, ItemDisplay.class, d -> dress(d, record, item, look, cfg.transform(),
                    new Vector3f(0f, 0f, 0f), cfg.scale()));
        }
        return record.withDisplay(display.getUniqueId());
    }

    private static void dress(ItemDisplay d, WorkbenchRecord record, ItemStack item, String look,
                              ItemDisplay.ItemDisplayTransform transform, Vector3f translation, float scale) {
        d.setItemStack(item);
        d.setItemDisplayTransform(transform);
        d.setTransformation(new Transformation(translation, new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
        d.setBillboard(Display.Billboard.FIXED);
        d.setPersistent(true);
        d.setInvulnerable(true);
        d.setSilent(true);
        d.setGravity(false);
        d.getPersistentDataContainer().set(LINKED_DISPLAY, PersistentDataType.STRING, record.key());
        d.getPersistentDataContainer().set(DISPLAY_LOOK, PersistentDataType.STRING, look);
    }

    /** The look a display of this kind should have now: changes whenever its mode, item or geometry does. */
    private String lookOf(BlockKind kind) {
        if (plugin.config().displayMode(kind) == CraftBridgeConfig.DisplayMode.MODEL) {
            return "model/" + kind.modelData() + "/" + plugin.config().modelScale(kind);
        }
        String texture = plugin.config().headTexture(kind);
        String item = texture == null || texture.isBlank()
                ? plugin.config().displayMaterial(kind).name()
                : "head:" + Integer.toHexString(texture.hashCode());
        return "head/" + item + "/" + plugin.config().displayFor(kind);
    }

    // ---- who sees model displays --------------------------------------------------------

    /**
     * Set the rule for who sees model displays (a player whose client loaded the pack; Bedrock
     * players only when bedrock.show-displays is on). Head displays are seen by everyone.
     */
    public void viewers(Predicate<Player> rule) {
        this.viewers = rule == null ? player -> false : rule;
    }

    private boolean seesModels(Player player) {
        try {
            return viewers.test(player);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Show every loaded model display to one player (their pack just loaded, or they joined with it). */
    public int showTo(Player player) {
        int n = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (isOurs(display) && !display.isVisibleByDefault()) {
                    player.showEntity(plugin, display);
                    n++;
                }
            }
        }
        return n;
    }

    /** Hide every loaded model display from one player again (their pack was declined or failed). */
    public void hideFrom(Player player) {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (isOurs(display) && !display.isVisibleByDefault()) {
                    player.hideEntity(plugin, display);
                }
            }
        }
    }

    /**
     * Paper forgets a player's "show this hidden entity" when the entity unloads, so model
     * displays that just loaded with a chunk are shown to their viewers again.
     */
    private void showLoaded(Entity display) {
        if (display.isVisibleByDefault() || !display.isValid()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (seesModels(player)) {
                player.showEntity(plugin, display);
            }
        }
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
        for (Entity e : chunk.getEntities()) {
            if (isOurs(e)) {
                showLoaded(e);
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
            // An entry for this table (or naming this display) is in linked-workbenches.yml but
            // did not load: not an orphan, just unreadable for now. Keep it for when it is fixed.
            return !store.isHeld(key, display.getUniqueId());
        }
        Block block = record.block();
        if (block == null || block.getType() != record.kind().block()) {
            return true;
        }
        // A second display for the same table (e.g. after a crash mid-write) is an orphan too.
        return record.display() != null && !record.display().equals(display.getUniqueId());
    }

    /**
     * Make one loaded record consistent: table gone → drop the record and its display;
     * display gone or out of date → respawn. Returns true if something was respawned.
     */
    private boolean heal(WorkbenchRecord record) {
        Block block = record.block();
        if (block == null) {
            return false;
        }
        if (block.getType() != record.kind().block()) {
            plugin.getLogger().info(record.kind().displayName() + " at " + record.key() + " is no longer a "
                    + record.kind().block() + "; forgetting it.");
            remove(record.display());
            store.remove(record.key());
            return false;
        }
        Entity e = record.display() == null ? null : Bukkit.getEntity(record.display());
        if (e instanceof ItemDisplay && e.isValid()
                && lookOf(record.kind()).equals(e.getPersistentDataContainer().get(DISPLAY_LOOK, PersistentDataType.STRING))) {
            return false;
        }
        // Missing, or spawned for a look config.yml no longer asks for: spawn it anew.
        store.put(spawn(record));
        return true;
    }

    public List<WorkbenchRecord> records() {
        return new ArrayList<>(store.all());
    }
}
