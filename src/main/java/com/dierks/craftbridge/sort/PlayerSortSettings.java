package com.dierks.craftbridge.sort;

/** One player's sorting preferences. Immutable; use the {@code with*} copies. */
public record PlayerSortSettings(SortTrigger trigger, boolean sortPlayerInventory, boolean feedback) {

    public PlayerSortSettings withTrigger(SortTrigger t) {
        return new PlayerSortSettings(t, sortPlayerInventory, feedback);
    }

    public PlayerSortSettings withSortPlayerInventory(boolean v) {
        return new PlayerSortSettings(trigger, v, feedback);
    }

    public PlayerSortSettings withFeedback(boolean v) {
        return new PlayerSortSettings(trigger, sortPlayerInventory, v);
    }
}
