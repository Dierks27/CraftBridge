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

    /**
     * One usable container: its block (one half for double chests) and its full inventory.
     *
     * @param keepOne true for a golem chest (or any container an "All but one" craft reads):
     *                every read and every pull leaves the last item of each slot where it is,
     *                by the rules in {@link TakePlanner}
     */
    public record Source(Block block, Inventory inventory, boolean keepOne) {
        public Source(Block block, Inventory inventory) {
            this(block, inventory, false);
        }

        public Location location() {
            return block.getLocation();
        }

        /** This container, keeping one of every slot. */
        public Source keepingOne() {
            return keepOne ? this : new Source(block, inventory, true);
        }
    }

    private final CraftBridgePlugin plugin;
    private java.util.function.Supplier<Set<String>> terminals = Set::of;
    /** "Is this container a golem chest?" (either half, for a double chest); none unless wired. */
    private java.util.function.Predicate<List<Block>> golem = blocks -> false;

    public StorageScanner(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Container blocks that are terminals (Combo Chest barrels) and therefore never storage, for any scan. */
    public void terminals(java.util.function.Supplier<Set<String>> terminals) {
        this.terminals = terminals;
    }

    /** How to tell a golem chest: given every block behind one inventory (both halves of a double chest). */
    public void golemChests(java.util.function.Predicate<List<Block>> golem) {
        this.golem = golem == null ? blocks -> false : golem;
    }

    /** Every source keeping one of every slot: what an "All but one" craft reads and pulls from. */
    public static List<Source> keepingOne(List<Source> sources) {
        List<Source> out = new ArrayList<>(sources.size());
        for (Source source : sources) {
            out.add(source.keepingOne());
        }
        return out;
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
            // Both halves of a double chest: the inventory is shared, so the lock or claim of
            // whichever half the scan reached first must not decide for the other.
            if (!plugin.containerAccess().canUseAll(player, block, inventory, protection)) {
                continue;
            }
            sources.add(new Source(block, inventory, isGolem(block, inventory)));
        }
        return sources;
    }

    /** Whether a golem-chest mark on any block behind this inventory makes it keep one per slot. */
    private boolean isGolem(Block block, Inventory inventory) {
        try {
            return golem.test(com.dierks.craftbridge.integration.ContainerAccess.blocksBehind(block, inventory));
        } catch (RuntimeException ex) {
            return false; // a mark that cannot be read is no mark: never hide items over it
        }
    }

    /**
     * Totals per item (key = the item with amount 1), in first-seen order: what may be
     * <em>taken</em>, so a golem chest counts one less per slot, and an item of which it only
     * holds singles is not listed at all.
     */
    public Map<ItemStack, Integer> aggregate(List<Source> sources) {
        Map<ItemStack, Integer> totals = new LinkedHashMap<>();
        for (Source source : sources) {
            for (ItemStack stack : source.inventory().getStorageContents()) {
                if (Items.isEmpty(stack)) {
                    continue;
                }
                int takeable = TakePlanner.takeable(stack.getAmount(), source.keepOne());
                if (takeable > 0) {
                    totals.merge(keyOf(stack), takeable, Integer::sum);
                }
            }
        }
        return totals;
    }

    /** Per slot of {@code contents}, how many of {@code key} it holds (0 for anything else). */
    private static int[] amountsOf(ItemStack[] contents, ItemStack key) {
        int[] amounts = new int[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (!Items.isEmpty(stack) && stack.isSimilar(key)) {
                amounts[slot] = stack.getAmount();
            }
        }
        return amounts;
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
        sources = standing(sources);
        int moved = 0;
        for (Source source : sources) {
            if (wanted <= 0) {
                break;
            }
            Inventory inv = source.inventory();
            ItemStack[] contents = inv.getStorageContents();
            int[] amounts = amountsOf(contents, key);
            int[] plan = TakePlanner.plan(amounts, source.keepOne(), wanted);
            for (int slot : TakePlanner.order(amounts, source.keepOne())) {
                if (plan[slot] <= 0) {
                    continue;
                }
                ItemStack stack = contents[slot];
                int take = plan[slot];
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
        }
        return moved;
    }

    /**
     * Take up to {@code wanted} items matching {@code key} out of the sources and return
     * them as stacks, remembering which container each came from. Used by the JEI
     * transfer to feed the crafting grid directly.
     */
    public List<Pulled> pull(List<Source> sources, ItemStack key, int wanted) {
        sources = standing(sources);
        List<Pulled> out = new ArrayList<>();
        for (Source source : sources) {
            if (wanted <= 0) {
                break;
            }
            Inventory inv = source.inventory();
            ItemStack[] contents = inv.getStorageContents();
            int[] amounts = amountsOf(contents, key);
            int[] plan = TakePlanner.plan(amounts, source.keepOne(), wanted);
            // Visit the slots in the planner's own order (fullest first for a golem chest), so
            // the Pulled list reads the way the items were actually taken.
            for (int slot : TakePlanner.order(amounts, source.keepOne())) {
                int take = plan[slot];
                if (take <= 0) {
                    continue;
                }
                ItemStack stack = contents[slot];
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
        }
        return out;
    }

    /**
     * The sources whose block is still a storage block. A list is scanned at one moment and
     * used at another; a container broken in between must not be pulled from (a broken
     * shulker box's contents already went into its dropped item) or deposited into.
     */
    private static List<Source> standing(List<Source> sources) {
        List<Source> out = new ArrayList<>(sources.size());
        for (Source source : sources) {
            if (isStorageBlock(source.block().getType())) {
                out.add(source);
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
        sources = standing(sources);
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

    /**
     * How many items matching {@code key} may be taken from the sources in total: everything,
     * except that a golem chest keeps one per slot ({@link TakePlanner#takeable}).
     */
    public static int count(List<Source> sources, ItemStack key) {
        sources = standing(sources);
        long n = 0;
        Map<Inventory, Boolean> seen = new HashMap<>();
        for (Source source : sources) {
            if (seen.put(source.inventory(), Boolean.TRUE) != null) {
                continue;
            }
            n += TakePlanner.takeable(amountsOf(source.inventory().getStorageContents(), key), source.keepOne());
        }
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }
}
