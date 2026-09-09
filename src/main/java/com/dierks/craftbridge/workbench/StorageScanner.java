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
    private java.util.function.Supplier<Set<String>> terminals = Set::of;

    public StorageScanner(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Container blocks that are terminals (Combo Chest barrels) and therefore never storage, for any scan. */
    public void terminals(java.util.function.Supplier<Set<String>> terminals) {
        this.terminals = terminals;
    }

    public static boolean isStorageBlock(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(type);
    }

    /** Scan the cube of {@code radius} around the table, nearest containers first. */
    public List<Source> scan(Player player, Location center, int radius) {
        return scan(player, center, radius, Set.of());
    }

    /** As {@link #scan(Player, Location, int)} but also skipping the given container blocks. */
    public List<Source> scan(Player player, Location center, int radius, Set<Location> exclude) {
        World world = center.getWorld();
        Set<String> terminalBlocks = terminals.get();
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
            if (exclude.contains(block.getLocation()) || terminalBlocks.contains(BlockKeys.of(block))) {
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
     * Put {@code stack} into nearby storage, in the order {@link DepositPlanner} defines:
     * top up partial stacks of the same item (nearest container first), then an empty slot
     * in a container that already holds it, then an empty slot in the nearest container
     * with space. Returns what did not fit (null when everything was stored) — a deposit
     * never destroys part of a stack. {@code sources} is already permission-filtered, so
     * locked and protected containers are skipped at every step.
     */
    public ItemStack deposit(List<Source> sources, ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return null;
        }
        ItemStack item = stack.clone();
        List<Inventory> targets = new ArrayList<>();
        List<DepositPlanner.Container> containers = new ArrayList<>();
        Set<Inventory> seen = new HashSet<>();
        Set<String> terminalBlocks = terminals.get();
        for (Source source : sources) {
            Inventory inventory = source.inventory();
            // Belt and braces: a terminal's own container is never a deposit destination, even
            // if a stale source list somehow still holds it. Overflow stays with the player.
            if (terminalBlocks.contains(BlockKeys.of(source.block()))) {
                continue;
            }
            if (!seen.add(inventory) || !accepts(inventory, item)) {
                continue;
            }
            ItemStack[] contents = inventory.getStorageContents();
            int[] slots = new int[contents.length];
            for (int i = 0; i < contents.length; i++) {
                ItemStack held = contents[i];
                slots[i] = Items.isEmpty(held) ? DepositPlanner.EMPTY
                        : (held.isSimilar(item) ? held.getAmount() : DepositPlanner.OTHER);
            }
            targets.add(inventory);
            containers.add(new DepositPlanner.Container(slots,
                    Math.min(item.getMaxStackSize(), inventory.getMaxStackSize())));
        }

        DepositPlanner.Plan plan = DepositPlanner.plan(containers, item.getAmount());
        Map<Integer, ItemStack[]> edited = new LinkedHashMap<>();
        for (DepositPlanner.Move move : plan.moves()) {
            ItemStack[] contents = edited.computeIfAbsent(move.container(),
                    i -> targets.get(i).getStorageContents());
            ItemStack held = contents[move.slot()];
            if (Items.isEmpty(held)) {
                ItemStack put = item.clone();
                put.setAmount(move.amount());
                contents[move.slot()] = put;
            } else {
                held.setAmount(held.getAmount() + move.amount());
                contents[move.slot()] = held;
            }
        }
        for (Map.Entry<Integer, ItemStack[]> entry : edited.entrySet()) {
            targets.get(entry.getKey()).setStorageContents(entry.getValue());
        }

        if (plan.leftover() <= 0) {
            return null;
        }
        ItemStack left = item.clone();
        left.setAmount(plan.leftover());
        return left;
    }

    /** Vanilla never lets a shulker box hold another one; neither do we. */
    private static boolean accepts(Inventory inventory, ItemStack stack) {
        return !Tag.SHULKER_BOXES.isTagged(stack.getType())
                || !(inventory.getHolder(false) instanceof org.bukkit.block.ShulkerBox);
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
