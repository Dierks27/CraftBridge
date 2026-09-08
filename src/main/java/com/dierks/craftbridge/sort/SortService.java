package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit side of sorting: decides what is sortable, turns inventories into
 * {@link SortAlgorithm} entries and writes the result back. Never moves items out of
 * the inventory and never touches the cursor.
 */
public final class SortService {

    /** Main-inventory slots of a player (hotbar 0-8 and armour/offhand are left alone). */
    private static final int PLAYER_MAIN_FROM = 9;
    private static final int PLAYER_MAIN_TO = 36;

    private final CraftBridgePlugin plugin;
    private final SortCategoryRules categories;
    private final SortAlgorithm<ItemStack> algorithm = new SortAlgorithm<>();

    public SortService(CraftBridgePlugin plugin, SortCategoryRules categories) {
        this.plugin = plugin;
        this.categories = categories;
    }

    /** Chests (single/double/trapped), barrels and placed shulker boxes only. */
    public boolean isSortableContainer(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof Chest || holder instanceof DoubleChest
                || holder instanceof Barrel || holder instanceof ShulkerBox;
    }

    /** True if this block type is one we sort when punched. */
    public boolean isSortableBlock(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || org.bukkit.Tag.SHULKER_BOXES.isTagged(type);
    }

    /** Sort every slot of a container. Returns false if nothing changed. */
    public boolean sortContainer(Inventory inventory) {
        return sortRange(inventory, 0, inventory.getSize());
    }

    /** Sort the player's main inventory rows (not the hotbar, armour or offhand). */
    public boolean sortPlayerInventory(PlayerInventory inventory) {
        return sortRange(inventory, PLAYER_MAIN_FROM, PLAYER_MAIN_TO);
    }

    public void feedback(Player player, PlayerSortSettings settings, Location where) {
        if (!settings.feedback()) {
            return;
        }
        Location at = where != null ? where : player.getLocation();
        player.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        if (where != null) {
            where.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, where.clone().add(0.5, 1.1, 0.5), 6, 0.3, 0.2, 0.3, 0);
        }
    }

    private boolean sortRange(Inventory inventory, int from, int to) {
        List<SortAlgorithm.Entry<ItemStack>> entries = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (Items.isEmpty(stack)) {
                continue;
            }
            ItemStack key = stack.clone();
            key.setAmount(1);
            Material type = stack.getType();
            ItemMeta meta = stack.getItemMeta();
            String displayName = "";
            if (meta != null && meta.hasDisplayName()) {
                displayName = Items.describe(stack);
            }
            int category = categories.categoryOf(type.name(), type.isBlock(), type.isEdible());
            int maxStack = Math.max(1, Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()));
            entries.add(new SortAlgorithm.Entry<>(key, stack.getAmount(), maxStack, category, type.name(), displayName));
        }
        if (entries.isEmpty()) {
            return false;
        }
        List<SortAlgorithm.Stack<ItemStack>> sorted = algorithm.sort(entries);
        if (sorted.size() > to - from) {
            // Cannot happen (merging only shrinks), but never lose items if it somehow does.
            plugin.getLogger().warning("Sort produced more stacks than slots; aborting sort.");
            return false;
        }
        boolean changed = false;
        int slot = from;
        for (SortAlgorithm.Stack<ItemStack> s : sorted) {
            ItemStack out = s.key().clone();
            out.setAmount(s.amount());
            ItemStack current = inventory.getItem(slot);
            if (current == null || !current.equals(out)) {
                inventory.setItem(slot, out);
                changed = true;
            }
            slot++;
        }
        for (; slot < to; slot++) {
            if (!Items.isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
                changed = true;
            }
        }
        return changed;
    }
}
