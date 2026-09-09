package com.dierks.craftbridge.workbench;

/**
 * How much to take out of storage when a phantom slot is clicked. Bukkit-free so the bounds
 * can be unit tested: the v0.2 version of this pulled an unbounded amount and had no defined
 * behaviour when the inventory filled up, which is why click-to-pull was removed. Every
 * answer here is bounded by what storage actually holds <em>and</em> by the room the player
 * actually has, so a pull can never overflow and never needs to drop items on the ground.
 */
public final class PullPlanner {

    public enum Mode {
        /** One stack. */
        ONE,
        /** Half a stack, rounded up. */
        HALF,
        /** As much as fits. */
        ALL
    }

    private PullPlanner() {
    }

    /**
     * @param mode      what the click asked for
     * @param available how many of this item nearby storage holds right now
     * @param maxStack  the item's stack size
     * @param freeSlots empty inventory slots available to receive it, including the clicked one
     * @return how many items to move; never more than {@code available}, never more than
     *         {@code freeSlots * maxStack}, never negative
     */
    public static int amount(Mode mode, int available, int maxStack, int freeSlots) {
        if (available <= 0 || maxStack <= 0 || freeSlots <= 0) {
            return 0;
        }
        int room = capacity(freeSlots, maxStack);
        int wanted = switch (mode) {
            case ONE -> maxStack;
            case HALF -> (maxStack + 1) / 2;
            case ALL -> room;
        };
        return Math.max(0, Math.min(Math.min(wanted, available), room));
    }

    /** {@code freeSlots * maxStack}, saturating rather than overflowing. */
    private static int capacity(int freeSlots, int maxStack) {
        long room = (long) freeSlots * (long) maxStack;
        return room > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) room;
    }
}
