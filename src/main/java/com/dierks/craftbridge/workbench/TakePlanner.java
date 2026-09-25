package com.dierks.craftbridge.workbench;

/**
 * How much of one item type may be taken out of one container, and from which slots.
 *
 * <p>Most containers give up everything. A <b>golem chest</b> (a chest or barrel marked with the
 * Golem Chest Marker) and every container touched by an "All but one" craft keep the
 * <em>last</em> item of every slot: a copper golem, a hopper filter or a sorting system that
 * matches on "this slot already holds that item" keeps working, because the slot is never
 * emptied. Such a container reports only what it can give — {@code amount - 1} per slot — so
 * every reader of storage (the phantom slots, the client panel, the Combo Chest list, JEI's
 * craftability check) sees the same number the pull will honour.
 *
 * <p>Bukkit-free on purpose: slot contents are plain amounts (0 or less for a slot that does not
 * hold the item), so every rule here is a unit test.
 */
public final class TakePlanner {

    private TakePlanner() {
    }

    /** What one slot holding {@code amount} of the item can give. */
    public static int takeable(int amount, boolean keepOne) {
        if (amount <= 0) {
            return 0;
        }
        return keepOne ? amount - 1 : amount;
    }

    /** What the whole container can give, summed over its slots; saturates rather than overflowing. */
    public static int takeable(int[] amounts, boolean keepOne) {
        long total = 0;
        for (int amount : amounts) {
            total += takeable(amount, keepOne);
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * How many to take from each slot to gather up to {@code wanted} items.
     *
     * <p>An ordinary container is drained in slot order, as it always was. A container that keeps
     * one is taken from its <b>fullest slots first</b> (ties in slot order) and never below one
     * per slot, so a pull leaves the most even spread of single items behind rather than a row
     * of empty-but-one slots next to a full stack.
     *
     * @param amounts per slot, how many of the item it holds (0 or less: not this item)
     * @return per slot, how many to take; the sum is at most {@code wanted} and at most
     *         {@link #takeable(int[], boolean)}
     */
    public static int[] plan(int[] amounts, boolean keepOne, int wanted) {
        int[] take = new int[amounts.length];
        if (wanted <= 0) {
            return take;
        }
        int left = wanted;
        for (int slot : order(amounts, keepOne)) {
            if (left <= 0) {
                break;
            }
            int give = Math.min(left, takeable(amounts[slot], keepOne));
            if (give > 0) {
                take[slot] = give;
                left -= give;
            }
        }
        return take;
    }

    /** Slot visiting order: slot order, or fullest first (stable) when keeping one. */
    public static int[] order(int[] amounts, boolean keepOne) {
        Integer[] slots = new Integer[amounts.length];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        if (keepOne) {
            // Arrays.sort on objects is stable, so equal amounts stay in slot order.
            java.util.Arrays.sort(slots, (a, b) -> Integer.compare(amounts[b], amounts[a]));
        }
        int[] out = new int[slots.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = slots[i];
        }
        return out;
    }
}
