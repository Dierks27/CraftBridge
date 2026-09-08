package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the storage a Linked Workbench may draw from: every chest (double chests count
 * once), barrel and placed shulker box within the configured radius that the player is
 * allowed to use. Hoppers, furnaces and other containers are deliberately ignored.
 */
public final class StorageScanner {

    /** One usable container: its block (one half for double chests) and its full inventory. */
    public record Source(Block block, Inventory inventory) {
        public Location location() {
            return block.getLocation();
        }
    }

    private final CraftBridgePlugin plugin;

    public StorageScanner(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isStorageBlock(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(type);
    }

    /** Scan the cube of {@code radius} around the table, nearest containers first. */
    public List<Source> scan(Player player, Location center, int radius) {
        return scan(player, center, radius, Set.of());
    }

    /** As {@link #scan(Player, Location, int)} but skipping the given container blocks (e.g. a terminal's own barrel). */
    public List<Source> scan(Player player, Location center, int radius, Set<Location> exclude) {
        World world = center.getWorld();
        List<Source> sources = new ArrayList<>();
        Set<Location> seenInventories = new HashSet<>();
        boolean protection = plugin.config().workbenchRespectProtection();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        List<Block> candidates = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = Math.max(world.getMinHeight(), cy - radius); y <= Math.min(world.getMaxHeight() - 1, cy + radius); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isStorageBlock(block.getType())) {
                        candidates.add(block);
                    }
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(center), b.getLocation().distanceSquared(center)));
        for (Block block : candidates) {
            if (exclude.contains(block.getLocation())) {
                continue;
            }
            if (!(block.getState(false) instanceof Container container)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            Location invKey = inventory.getLocation();
            if (invKey != null && !seenInventories.add(invKey)) {
                continue; // the other half of a double chest we already have
            }
            if (!plugin.containerAccess().canUse(player, block, protection)) {
                continue;
            }
            sources.add(new Source(block, inventory));
        }
        return sources;
    }

    /** Totals per item (key = the item with amount 1), in first-seen order. */
    public Map<ItemStack, Integer> aggregate(List<Source> sources) {
        Map<ItemStack, Integer> totals = new LinkedHashMap<>();
        for (Source source : sources) {
            for (ItemStack stack : source.inventory().getStorageContents()) {
                if (Items.isEmpty(stack)) {
                    continue;
                }
                totals.merge(keyOf(stack), stack.getAmount(), Integer::sum);
            }
        }
        return totals;
    }

    public static ItemStack keyOf(ItemStack stack) {
        ItemStack key = stack.clone();
        key.setAmount(1);
        return key;
    }

    /**
     * Move up to {@code wanted} items matching {@code key} from the sources into the
     * player's inventory. Stops when the inventory is full; anything that did not fit
     * goes back where it came from. Returns how many items were moved.
     */
    public int pullToPlayer(Player player, List<Source> sources, ItemStack key, int wanted) {
        int moved = 0;
        for (Source source : sources) {
            Inventory inv = source.inventory();
            ItemStack[] contents = inv.getStorageContents();
            for (int slot = 0; slot < contents.length && wanted > 0; slot++) {
                ItemStack stack = contents[slot];
                if (Items.isEmpty(stack) || !stack.isSimilar(key)) {
                    continue;
                }
                int take = Math.min(wanted, stack.getAmount());
                ItemStack taken = stack.clone();
                taken.setAmount(take);
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(taken);
                int notFitting = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                int actuallyMoved = take - notFitting;
                if (actuallyMoved <= 0) {
                    return moved; // inventory full
                }
                int remaining = stack.getAmount() - actuallyMoved;
                if (remaining <= 0) {
                    inv.setItem(slot, null);
                } else {
                    stack.setAmount(remaining);
                    inv.setItem(slot, stack);
                }
                moved += actuallyMoved;
                wanted -= actuallyMoved;
                if (notFitting > 0) {
                    return moved;
                }
            }
            if (wanted <= 0) {
                break;
            }
        }
        return moved;
    }

    /**
     * Take up to {@code wanted} items matching {@code key} out of the sources and return
     * them as stacks, remembering which container each came from. Used by the JEI
     * transfer to feed the crafting grid directly.
     */
    public List<Pulled> pull(List<Source> sources, ItemStack key, int wanted) {
        List<Pulled> out = new ArrayList<>();
        for (Source source : sources) {
            Inventory inv = source.inventory();
            ItemStack[] contents = inv.getStorageContents();
            for (int slot = 0; slot < contents.length && wanted > 0; slot++) {
                ItemStack stack = contents[slot];
                if (Items.isEmpty(stack) || !stack.isSimilar(key)) {
                    continue;
                }
                int take = Math.min(wanted, stack.getAmount());
                ItemStack taken = stack.clone();
                taken.setAmount(take);
                int remaining = stack.getAmount() - take;
                if (remaining <= 0) {
                    inv.setItem(slot, null);
                } else {
                    stack.setAmount(remaining);
                    inv.setItem(slot, stack);
                }
                out.add(new Pulled(taken, source));
                wanted -= take;
            }
            if (wanted <= 0) {
                break;
            }
        }
        return out;
    }

    /** Items taken from one source. */
    public record Pulled(ItemStack stack, Source source) {
    }

    /**
     * Put {@code stack} into nearby storage: first into a container that already holds
     * that item type, else the nearest container with a free slot. Returns what did not
     * fit (empty when everything was stored). Sources are already permission-filtered.
     */
    public ItemStack deposit(List<Source> sources, ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return null;
        }
        ItemStack remaining = stack.clone();
        for (Source source : sources) {
            if (!holds(source.inventory(), remaining)) {
                continue;
            }
            remaining = addAll(source.inventory(), remaining);
            if (Items.isEmpty(remaining)) {
                return null;
            }
        }
        for (Source source : sources) {
            if (source.inventory().firstEmpty() < 0) {
                continue;
            }
            remaining = addAll(source.inventory(), remaining);
            if (Items.isEmpty(remaining)) {
                return null;
            }
        }
        return remaining;
    }

    private static boolean holds(Inventory inventory, ItemStack key) {
        for (ItemStack s : inventory.getStorageContents()) {
            if (!Items.isEmpty(s) && s.isSimilar(key)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack addAll(Inventory inventory, ItemStack stack) {
        Map<Integer, ItemStack> left = inventory.addItem(stack);
        if (left.isEmpty()) {
            return null;
        }
        return left.values().iterator().next();
    }

    /** How many items matching {@code key} the sources hold in total. */
    public static int count(List<Source> sources, ItemStack key) {
        int n = 0;
        Map<Inventory, Boolean> seen = new HashMap<>();
        for (Source source : sources) {
            if (seen.put(source.inventory(), Boolean.TRUE) != null) {
                continue;
            }
            for (ItemStack stack : source.inventory().getStorageContents()) {
                if (!Items.isEmpty(stack) && stack.isSimilar(key)) {
                    n += stack.getAmount();
                }
            }
        }
        return n;
    }
}
