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
 * The block's look: one persistent {@link ItemDisplay} per placed block, over the block
 * underneath.
 *
 * <p>With {@code display.invisible-block} on (the default) that block is an invisible barrier,
 * so the display is all anyone sees and everyone sees it: in {@code display.mode: model} it holds
 * the block's item with CraftBridge's custom model data, which a client with the pack draws as
 * the model and a client without it as the plain vanilla block. With it off the block underneath
 * is the vanilla block itself; a model display is then hidden by default and
 * {@link #showTo(Player)} shows it to each player whose client loaded the resource pack (the
 * {@link #viewers(Predicate)} rule, set by the resource-pack feature), while everyone else sees
 * the real block. In {@code head} mode it holds the textured head, scaled to cover the block, and
 * everyone sees it.
 *
 * <p>Every display carries the PDC tag {@code craftbridge:linked_display} = the table
 * key, and the table record stores the display UUID, so each side can find the other.
 * {@link #sweep()} (startup) and {@link #sweepChunk(Chunk)} (when a chunk's entities
 * load) remove orphans whose table is gone, respawn displays that went missing, and swap the
 * block underneath when display.invisible-block changed — that is what makes a WorldEdit/Towny
 * regen wiping the table self-heal, and what converts the blocks placed before 0.16.
 */
public final class DisplayManager {

    public static final NamespacedKey LINKED_DISPLAY = Keys.key("linked_display");
    /**
     * What a display was spawned to look like (mode, item and geometry). A display whose look
     * no longer matches config.yml, e.g. after the upgrade that switched to mode: model, is
     * respawned by the sweeps.
     */
    public static final NamespacedKey DISPLAY_LOOK = Keys.key("display_look");
    /** On a lid's display: the key of the block it belongs to (see {@link Variants}). */
    public static final NamespacedKey LID_OF = Keys.key("lid_of");
    /** How long a lid takes to swing open or shut, in ticks (a chest's is about as quick). */
    static final int LID_TICKS = 8;

    /**
     * The extra models a kind's pack has ({@link com.dierks.craftbridge.pack.BlockVariants}): an
     * {@code _active} look for while it is in use, and a lid with the hinge it swings on (pixels),
     * null when there is none.
     */
    public record Variants(boolean active, double[] lidHinge) {
        public static final Variants NONE = new Variants(false, null);
    }

    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private final WorkbenchItems items;
    /** Told about every block swapped under a record (Bedrock players get their stand-in). */
    private java.util.function.Consumer<WorkbenchRecord> afterConvert = record -> { };
    /** Blocks swapped to what display.invisible-block asks for, counted for the startup log. */
    private int converted;
    /** Who sees model displays; nobody until the resource-pack feature says otherwise. */
    private Predicate<Player> viewers = player -> false;
    private java.util.Map<BlockKind, Variants> variants = java.util.Map.of();
    /** Who has each block open (its menu), by block key: in use while this is not empty. */
    private final java.util.Map<String, java.util.Set<UUID>> users = new java.util.HashMap<>();

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
        removeLids(record);
        BlockKind kind = record.kind();
        boolean model = plugin.config().displayMode(kind) == CraftBridgeConfig.DisplayMode.MODEL;
        String look = lookOf(kind);
        boolean inUse = users.containsKey(record.key());
        ItemStack item = model && inUse && variantsOf(kind).active()
                ? WorkbenchItems.modelItem(kind, com.dierks.craftbridge.pack.BlockVariants.ACTIVE) : items.displayItem(kind);
        ItemDisplay display;
        if (model && plugin.config().invisibleBlock(kind)) {
            // Over an invisible barrier everyone sees the display: the model with the pack, the
            // vanilla block (the item's fallback) without it. A barrier lets light through, so the
            // entity sits at the centre of the block and is drawn with the block's own light. The
            // model's front is north. An item display draws the model's north face along its own
            // facing, so it faces the way the record's yaw points: toward the player who placed it.
            float scale = plugin.config().modelScale(kind);
            Location at = new Location(world, record.x() + 0.5, record.y() + 0.5, record.z() + 0.5,
                    record.yaw(), 0f);
            display = world.spawn(at, ItemDisplay.class, d -> dress(d, record, item, look,
                    ItemDisplay.ItemDisplayTransform.NONE, new Vector3f(0f, 0f, 0f), scale));
            double[] hinge = variantsOf(kind).lidHinge();
            if (hinge != null) {
                // The lid is a second display in the same spot; only its transformation moves.
                ItemStack lid = WorkbenchItems.modelItem(kind, com.dierks.craftbridge.pack.BlockVariants.LID);
                world.spawn(at, ItemDisplay.class, d -> {
                    dress(d, record, lid, look, ItemDisplay.ItemDisplayTransform.NONE, new Vector3f(0f, 0f, 0f), scale);
                    d.getPersistentDataContainer().remove(LINKED_DISPLAY);
                    d.getPersistentDataContainer().set(LID_OF, PersistentDataType.STRING, record.key());
                    d.setTransformation(lidTransformation(hinge, scale, inUse));
                });
            }
        } else if (model) {
            // The entity sits on top of the block, so the light it is drawn with is the light
            // above the block, not the darkness inside it; the translation brings the model back
            // down over the block. The model's front is north, turned toward the player who placed
            // it as above.
            float scale = plugin.config().modelScale(kind);
            Location at = new Location(world, record.x() + 0.5, record.y() + 1.0, record.z() + 0.5,
                    record.yaw(), 0f);
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
            double[] hinge = plugin.config().invisibleBlock(kind) ? variantsOf(kind).lidHinge() : null;
            String lid = hinge == null ? "" : "/lid@" + hinge[0] + "," + hinge[1] + "," + hinge[2];
            // Over a barrier the display sits elsewhere and everyone sees it: a different look.
            return "model/" + kind.modelData() + "/" + plugin.config().modelScale(kind)
                    + (plugin.config().invisibleBlock(kind) ? "/over-barrier" : "")
                    + "/north-front" // 0.16.3 turned model displays around: respawn the older ones
                    + lid;
        }
        String texture = plugin.config().headTexture(kind);
        String item = texture == null || texture.isBlank()
                ? plugin.config().displayMaterial(kind).name()
                : "head:" + Integer.toHexString(texture.hashCode());
        return "head/" + item + "/" + plugin.config().displayFor(kind);
    }

    /** The extra models the pack has, by kind (set on enable, before the sweep). */
    public void variants(java.util.Map<BlockKind, Variants> found) {
        this.variants = found == null ? java.util.Map.of() : java.util.Map.copyOf(found);
    }

    private Variants variantsOf(BlockKind kind) {
        return variants.getOrDefault(kind, Variants.NONE);
    }

    // ---- in use: the active look and the lid ---------------------------------------------

    /** A player opened this block's menu: the first one puts it in use. */
    public void opened(WorkbenchRecord record, Player player) {
        java.util.Set<UUID> who = users.computeIfAbsent(record.key(), k -> new java.util.HashSet<>());
        if (who.add(player.getUniqueId()) && who.size() == 1) {
            show(record, true);
        }
    }

    /** A player closed it: the last one takes it out of use. */
    public void closed(WorkbenchRecord record, Player player) {
        java.util.Set<UUID> who = users.get(record.key());
        if (who != null && who.remove(player.getUniqueId()) && who.isEmpty()) {
            users.remove(record.key());
            show(record, false);
        }
    }

    /** Everything back to idle (the feature is being switched off or reloaded). */
    public void closeAll() {
        for (String key : new ArrayList<>(users.keySet())) {
            users.remove(key);
            WorkbenchRecord record = store.byKey(key);
            if (record != null) {
                show(record, false);
            }
        }
    }

    /** Swap to or from the active look, and swing the lid, with the chest's sound when there is one. */
    private void show(WorkbenchRecord live, boolean inUse) {
        WorkbenchRecord record = store.byKey(live.key());
        if (record == null || plugin.config().displayMode(record.kind()) != CraftBridgeConfig.DisplayMode.MODEL) {
            return;
        }
        Variants v = variantsOf(record.kind());
        if (v.active() && record.display() != null && Bukkit.getEntity(record.display()) instanceof ItemDisplay display) {
            display.setItemStack(inUse ? WorkbenchItems.modelItem(record.kind(), com.dierks.craftbridge.pack.BlockVariants.ACTIVE)
                    : items.displayItem(record.kind()));
        }
        if (v.lidHinge() == null || !plugin.config().invisibleBlock(record.kind())) {
            return;
        }
        boolean swung = false;
        for (ItemDisplay lid : lids(record)) {
            lid.setInterpolationDelay(0);
            lid.setInterpolationDuration(LID_TICKS);
            lid.setTransformation(lidTransformation(v.lidHinge(), plugin.config().modelScale(record.kind()), inUse));
            swung = true;
        }
        if (swung) {
            Location at = new Location(record.bukkitWorld(), record.x() + 0.5, record.y() + 0.5, record.z() + 0.5);
            at.getWorld().playSound(at, inUse ? org.bukkit.Sound.BLOCK_CHEST_OPEN : org.bukkit.Sound.BLOCK_CHEST_CLOSE,
                    0.5f, 1f);
        }
    }

    /** A lid's transformation, shut or open (see BlockVariants#lidTransform for the geometry). */
    private static Transformation lidTransformation(double[] hinge, float scale, boolean open) {
        if (!open) {
            return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f());
        }
        double[] t = com.dierks.craftbridge.pack.BlockVariants.lidTransform(hinge,
                com.dierks.craftbridge.pack.BlockVariants.LID_ANGLE, scale, new double[] {0, 0, 0});
        return new Transformation(new Vector3f((float) t[0], (float) t[1], (float) t[2]),
                new AxisAngle4f((float) t[3], 1f, 0f, 0f), new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /** The lid displays of a block: in the block's own space, tagged with its key. */
    private List<ItemDisplay> lids(WorkbenchRecord record) {
        World world = record.bukkitWorld();
        List<ItemDisplay> out = new ArrayList<>();
        if (world == null || !record.chunkLoaded()) {
            return out;
        }
        Location at = new Location(world, record.x() + 0.5, record.y() + 0.5, record.z() + 0.5);
        for (Entity e : world.getNearbyEntities(at, 0.75, 0.75, 0.75)) {
            if (e instanceof ItemDisplay d && record.key().equals(
                    d.getPersistentDataContainer().get(LID_OF, PersistentDataType.STRING))) {
                out.add(d);
            }
        }
        return out;
    }

    public void removeLids(WorkbenchRecord record) {
        for (ItemDisplay lid : lids(record)) {
            lid.remove();
        }
    }

    public void afterConvert(java.util.function.Consumer<WorkbenchRecord> action) {
        this.afterConvert = action == null ? record -> { } : action;
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

    /** Startup sweep over every loaded chunk. Returns "removed orphans / respawned / converted" counts. */
    public int[] sweep() {
        int removed = 0;
        int respawned = 0;
        converted = 0;
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if ((isOurs(display) && isOrphan(display)) || isOrphanLid(display)) {
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
        return new int[]{removed, respawned, converted};
    }

    /** Same as {@link #sweep()} but for one chunk whose entities just loaded. */
    public void sweepChunk(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if ((isOurs(e) && isOrphan(e)) || isOrphanLid(e)) {
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

    /** A lid whose block is gone, or whose block no longer has a lid. The heal respawns wanted ones. */
    private boolean isOrphanLid(Entity entity) {
        String key = entity instanceof ItemDisplay
                ? entity.getPersistentDataContainer().get(LID_OF, PersistentDataType.STRING) : null;
        if (key == null) {
            return false;
        }
        WorkbenchRecord record = store.byKey(key);
        if (record == null) {
            return !store.isHeld(key, entity.getUniqueId());
        }
        return !lookOf(record.kind()).contains("/lid@")
                || !lookOf(record.kind()).equals(entity.getPersistentDataContainer().get(DISPLAY_LOOK, PersistentDataType.STRING));
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
        if (block == null || !record.kind().standsOn(block.getType())) {
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
        if (!record.kind().standsOn(block.getType())) {
            plugin.getLogger().info(record.kind().displayName() + " at " + record.key() + " is no longer a "
                    + plugin.config().worldBlock(record.kind()) + "; forgetting it.");
            remove(record.display());
            store.remove(record.key());
            return false;
        }
        Material wanted = plugin.config().worldBlock(record.kind());
        if (block.getType() != wanted) {
            convert(record, block, wanted);
        }
        Entity e = record.display() == null ? null : Bukkit.getEntity(record.display());
        String look = lookOf(record.kind());
        if (e instanceof ItemDisplay && e.isValid()
                && look.equals(e.getPersistentDataContainer().get(DISPLAY_LOOK, PersistentDataType.STRING))
                && (!look.contains("/lid@") || !lids(record).isEmpty())) {
            return false;
        }
        // Missing, or spawned for a look config.yml no longer asks for: spawn it anew.
        store.put(spawn(record));
        return true;
    }

    /**
     * Swap the block under a placed block for the one display.invisible-block now asks for: a
     * crafting table or barrel placed before 0.16 becomes a barrier, or back. Whatever a barrel
     * holds is dropped first; a Combo Chest's barrel is never storage, but items that got in
     * before hoppers were kept out must not vanish with it.
     */
    private void convert(WorkbenchRecord record, Block block, Material wanted) {
        if (block.getState(false) instanceof org.bukkit.block.Container container) {
            Location drop = block.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack stack : container.getInventory().getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    block.getWorld().dropItemNaturally(drop, stack);
                }
            }
            container.getInventory().clear();
        }
        block.setType(wanted);
        converted++;
        afterConvert.accept(record);
        plugin.debug(record.kind().displayName() + " at " + record.key() + " now stands on " + wanted + ".");
    }

    public List<WorkbenchRecord> records() {
        return new ArrayList<>(store.all());
    }
}
