package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.jei.GridLayout;
import com.dierks.craftbridge.util.Items;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Taking items out of nearby storage on a click, and where they land.
 *
 * <p>There are two ways to click storage — a phantom slot, or the client mod's storage panel —
 * and they must behave identically, so the accounting lives here once rather than in each.
 * {@link PullPlanner} decides how much; this decides where it goes and what happens to what
 * will not fit.
 *
 * <ul>
 *   <li><b>One stack</b> (plain left click) and <b>half a stack</b> (right click) go to the
 *       cursor, like taking a stack out of a chest: one click, no intermediate state.</li>
 *   <li><b>As many as fit</b> (shift-click) go into the inventory.</li>
 *   <li>Anything that fits nowhere goes <em>back into storage</em>, never onto the floor.</li>
 * </ul>
 */
public final class StoragePull {

    /** What was moved, and where it went. */
    public record Result(int moved, boolean toCursor) {
        public boolean happened() {
            return moved > 0;
        }
    }

    private StoragePull() {
    }

    /** Empty inventory slots in the open view — phantoms are packet-only, so these really are empty. */
    public static int freeInventorySlots(InventoryView view) {
        int free = 0;
        for (int raw : GridLayout.WORKBENCH.inventorySlots()) {
            if (Items.isEmpty(view.getItem(raw))) {
                free++;
            }
        }
        return free;
    }

    /**
     * @param sources  the containers in range, already permission-checked by the scanner
     * @param key      the item type to take, as a single-item stack
     * @param overflow given anything that fit neither the cursor nor the inventory; the caller
     *                 puts it back into storage
     */
    public static Result pull(Player player, StorageScanner scanner, List<StorageScanner.Source> sources,
                              ItemStack key, PullPlanner.Mode mode, int freeSlots, Consumer<ItemStack> overflow) {
        boolean toCursor = mode != PullPlanner.Mode.ALL;
        int maxStack = Math.max(1, key.getMaxStackSize());
        int available = StorageScanner.count(sources, key);
        // To the cursor, one stack is the whole bound; into the inventory, the free slots are.
        int room = toCursor ? 1 : freeSlots;
        int want = PullPlanner.amount(mode, available, maxStack, room);
        if (want <= 0) {
            return new Result(0, toCursor);
        }

        int got = 0;
        for (StorageScanner.Pulled pulled : scanner.pull(sources, key, want)) {
            got += pulled.stack().getAmount();
        }
        if (got <= 0) {
            return new Result(0, toCursor);
        }

        if (toCursor) {
            int onCursor = Math.min(got, maxStack);
            player.setItemOnCursor(key.asQuantity(onCursor)); // sends the cursor packet itself
            int rest = got - onCursor;
            if (rest > 0) {
                player.getInventory().addItem(key.asQuantity(rest)).values().forEach(overflow);
            }
        } else {
            player.getInventory().addItem(key.asQuantity(got)).values().forEach(overflow);
        }
        return new Result(got, toCursor);
    }
}
