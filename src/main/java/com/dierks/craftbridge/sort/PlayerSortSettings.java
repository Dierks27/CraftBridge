package com.dierks.craftbridge.sort;

/**
 * One player's sorting preferences. Immutable; use the {@code with*} copies.
 *
 * @param middleClick sort on a middle-click in a container screen. Independent of
 *                    {@link #trigger}: it needs the CraftBridge-Client mod, a vanilla client
 *                    never sends the click at all, and it is never accidental, so it is an
 *                    extra way to sort rather than a choice that replaces the trigger.
 */
public record PlayerSortSettings(SortTrigger trigger, boolean sortPlayerInventory, boolean feedback,
                                 boolean middleClick) {

    public PlayerSortSettings withTrigger(SortTrigger t) {
        return new PlayerSortSettings(t, sortPlayerInventory, feedback, middleClick);
    }

    public PlayerSortSettings withSortPlayerInventory(boolean v) {
        return new PlayerSortSettings(trigger, v, feedback, middleClick);
    }

    public PlayerSortSettings withFeedback(boolean v) {
        return new PlayerSortSettings(trigger, sortPlayerInventory, v, middleClick);
    }

    public PlayerSortSettings withMiddleClick(boolean v) {
        return new PlayerSortSettings(trigger, sortPlayerInventory, feedback, v);
    }
}
