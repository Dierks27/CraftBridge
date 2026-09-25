package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.config.ShapeSpec;
import com.dierks.craftbridge.integration.ContainerAccess;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * Golem chests: chests and barrels that never give up the last item of any slot to a
 * CraftBridge pull.
 *
 * <p>A copper golem, a hopper filter or an item sorter decides where things go by what a slot
 * already holds. A Linked Workbench, a JEI transfer or the Combo Chest that empties such a slot
 * breaks the sorter silently. Right-clicking a chest, double chest or barrel with the <b>Golem
 * Chest Marker</b> toggles a mark in the block's own persistent data (both halves of a double
 * chest); {@link StorageScanner} then reads the container with {@link TakePlanner}'s keep-one
 * rule, so every count and every pull leaves one of each stack behind. Deposits are unaffected.
 *
 * <p>The mark lives on the block, so breaking the block forgets it and the dropped chest is an
 * ordinary chest. While a player holds the marker, marked containers near them show particles
 * to that player alone, so the marks can be found again.
 */
public final class GolemChests implements Listener {

    /** Config section of the marker's recipe and settings. */
    public static final String SECTION = "golem-chests";
    /** The block mark: a byte in the chest's or barrel's own persistent data. */
    public static final NamespacedKey MARK = Keys.key("golem_chest");
    /** The item tag that makes a stack the marker. */
    public static final NamespacedKey MARKER_ITEM = Keys.key("golem_marker");
    public static final NamespacedKey RECIPE = Keys.key("golem_marker");

    static final List<String> DEFAULT_SHAPE = List.of("C", "H", "S");
    static final Map<Character, Material> DEFAULT_INGREDIENTS =
            Map.of('C', Material.COPPER_INGOT, 'H', Material.HONEYCOMB, 'S', Material.STICK);

    /** Particles are refreshed this often; each burst lives about as long. */
    private static final int PARTICLE_TICKS = 10;

    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private boolean recipeRegistered;
    private int task = -1;
    private Particle particle;
    private int radius;

    public GolemChests(CraftBridgePlugin plugin, WorkbenchStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void enable() {
        particle = plugin.config().golemParticle();
        radius = plugin.config().golemRadius();
        registerRecipe();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (particle != null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::showMarks, PARTICLE_TICKS, PARTICLE_TICKS)
                    .getTaskId();
        }
        plugin.getLogger().info("Golem chests: marked chests and barrels keep the last item of every slot.");
    }

    public void disable() {
        if (task != -1) {
            plugin.getServer().getScheduler().cancelTask(task);
            task = -1;
        }
        HandlerList.unregisterAll(this);
        if (recipeRegistered) {
            Bukkit.removeRecipe(RECIPE);
            recipeRegistered = false;
        }
    }

    // ---- the mark ----------------------------------------------------------------------

    /** Blocks that can carry a mark: chests (both kinds) and barrels. */
    public static boolean canMark(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    /** True when this block carries the mark. Reads the live block entity; never a snapshot. */
    public static boolean isMarked(Block block) {
        if (!canMark(block.getType())) {
            return false;
        }
        return block.getState(false) instanceof TileState tile
                && tile.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE);
    }

    /** True when any of these blocks (both halves of a double chest) carries the mark. */
    public static boolean anyMarked(List<Block> blocks) {
        for (Block block : blocks) {
            if (isMarked(block)) {
                return true;
            }
        }
        return false;
    }

    private static void setMarked(Block block, boolean marked) {
        if (!(block.getState() instanceof TileState tile)) {
            return;
        }
        if (marked) {
            tile.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        } else {
            tile.getPersistentDataContainer().remove(MARK);
        }
        tile.update(true, false);
    }

    // ---- the marker item ---------------------------------------------------------------

    /** The Golem Chest Marker: a brush (a tool that is no crafting ingredient) wearing a tag. */
    public ItemStack markerItem(int amount) {
        ItemStack item = new ItemStack(Material.BRUSH);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item("<gold>Golem Chest Marker"));
            meta.lore(Text.lore(List.of(
                    "Right-click a chest or barrel to mark it",
                    "as a golem chest (again to unmark).",
                    "Workbenches, JEI and the Combo Chest then",
                    "always leave one of every stack in it.",
                    "",
                    "<yellow>Hold it <gray>to see the marked ones nearby.")));
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            meta.getPersistentDataContainer().set(MARKER_ITEM, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    public static boolean isMarker(ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(MARKER_ITEM, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(RECIPE);
        recipeRegistered = false;
        if (!plugin.config().recipeEnabled(SECTION)) {
            return;
        }
        List<String> shape = plugin.config().recipeShape(SECTION, DEFAULT_SHAPE);
        Map<Character, Material> ingredients = plugin.config().recipeIngredients(SECTION, DEFAULT_INGREDIENTS);
        String problem = ShapeSpec.problem(shape, ingredients.keySet());
        if (problem != null) {
            plugin.getLogger().warning(SECTION + ".recipe.shape is unusable — " + problem + " (found " + shape
                    + " with ingredients " + ingredients.keySet() + "). Using the built-in Golem Chest Marker recipe.");
            shape = DEFAULT_SHAPE;
            ingredients = DEFAULT_INGREDIENTS;
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(RECIPE, markerItem(1));
            recipe.shape(shape.toArray(new String[0]));
            for (Map.Entry<Character, Material> e : ingredients.entrySet()) {
                if (shape.stream().anyMatch(row -> row.indexOf(e.getKey()) >= 0)) {
                    recipe.setIngredient(e.getKey(), new RecipeChoice.MaterialChoice(e.getValue()));
                }
            }
            recipeRegistered = Bukkit.addRecipe(recipe);
            if (!recipeRegistered) {
                plugin.getLogger().warning("The Golem Chest Marker recipe was rejected by the server.");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("The Golem Chest Marker recipe could not be registered from " + SECTION
                    + ".recipe (" + ex + "); /craftbridge give <player> golemmarker still hands it out.");
        }
    }

    /** {@code /craftbridge give <player> golemmarker [amount]}. */
    public void give(org.bukkit.command.CommandSender sender, Player target, int amount) {
        ItemStack item = markerItem(amount);
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        sender.sendMessage(Text.msg("<green>Gave " + amount + "x Golem Chest Marker to " + target.getName() + "."));
    }

    // ---- toggling ----------------------------------------------------------------------

    /**
     * Right-click with the marker in the main hand toggles the mark on a chest, double chest or
     * barrel, and the container does not open. LOW, ahead of the listeners that would open it;
     * whether the player may touch the container is our own full access check, below.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (ContainerAccess.isSynthetic() || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND || !isMarker(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !canMark(block.getType()) || store.at(block) != null) {
            return; // a Combo Chest's barrel is a terminal, never storage: nothing to mark
        }
        // The marker never opens the container or brushes anything, whatever happens next.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        Player player = event.getPlayer();
        if (!(block.getState(false) instanceof Container container)) {
            return;
        }
        Inventory inventory = container.getInventory();
        // Marking changes what every CraftBridge pull may take, so only someone who may use the
        // container (both halves: lock, Towny, protection plugins) may mark it.
        if (!plugin.containerAccess().canUseAll(player, block, inventory, true)) {
            player.sendMessage(Text.msg("<red>You may not use that container, so you cannot mark it."));
            return;
        }
        List<Block> halves = ContainerAccess.blocksBehind(block, inventory);
        boolean mark = !anyMarked(halves);
        for (Block half : halves) {
            setMarked(half, mark);
        }
        String what = halves.size() > 1 ? "double chest" : Items.prettyMaterial(block.getType()).toLowerCase(java.util.Locale.ROOT);
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        if (mark) {
            player.sendMessage(Text.msg("<gold>Golem chest: <green>on <gray>— this " + what
                    + " now always keeps one of every stack."));
            player.playSound(at, Sound.ITEM_HONEYCOMB_WAX_ON, 1f, 1f);
        } else {
            player.sendMessage(Text.msg("<gold>Golem chest: <red>off <gray>— this " + what
                    + " gives up everything again."));
            player.playSound(at, Sound.ITEM_AXE_WAX_OFF, 1f, 1f);
        }
    }

    // ---- showing the marks -------------------------------------------------------------

    /**
     * Particles on marked containers near every player holding the marker, shown to that
     * player alone. Only players holding it cost anything, and for them only the block
     * entities of the few chunks in range are looked at, never every block.
     */
    private void showMarks() {
        if (particle == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!isMarker(player.getInventory().getItemInMainHand())
                    && !isMarker(player.getInventory().getItemInOffHand())) {
                continue;
            }
            try {
                showMarksTo(player);
            } catch (RuntimeException ex) {
                plugin.debug("Golem chests: could not show marks to " + player.getName() + ": " + ex);
            }
        }
    }

    private void showMarksTo(Player player) {
        Location eye = player.getLocation();
        World world = eye.getWorld();
        double maxDistance = (double) radius * radius;
        int minChunkX = (eye.getBlockX() - radius) >> 4, maxChunkX = (eye.getBlockX() + radius) >> 4;
        int minChunkZ = (eye.getBlockZ() - radius) >> 4, maxChunkZ = (eye.getBlockZ() + radius) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState state : chunk.getTileEntities(block -> canMark(block.getType()), false)) {
                    if (!(state instanceof TileState tile)
                            || !tile.getPersistentDataContainer().has(MARK, PersistentDataType.BYTE)) {
                        continue;
                    }
                    Location at = state.getLocation().add(0.5, 1.05, 0.5);
                    if (at.distanceSquared(eye) <= maxDistance) {
                        player.spawnParticle(particle, at, 3, 0.25, 0.1, 0.25, 0);
                    }
                }
            }
        }
    }
}
