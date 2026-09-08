package com.dierks.craftbridge.sort;

/** How a player asks for a container to be sorted. Chosen per player in {@code /sort settings}. */
public enum SortTrigger {
    /**
     * Shift + click the empty area outside the GUI with an empty cursor. Also accepted:
     * a plain outside click while the player is sneaking, because the vanilla client
     * historically sends outside clicks as THROW without the shift state.
     */
    SHIFT_CLICK_OUTSIDE("Shift-click outside", "Hold shift and click the dark area outside the chest."),
    /** Two clicks outside the GUI within {@code sorting.double-click-ms}, empty cursor. */
    DOUBLE_CLICK_OUTSIDE("Double-click outside", "Click the dark area outside the chest twice, quickly."),
    /** Sneak and left-click the container block in the world. */
    SNEAK_PUNCH_BLOCK("Sneak + punch block", "Crouch and left-click the chest block itself."),
    /** Only {@code /sort}. */
    COMMAND_ONLY("Command only", "Type /sort while the chest is open.");

    private final String title;
    private final String hint;

    SortTrigger(String title, String hint) {
        this.title = title;
        this.hint = hint;
    }

    public String title() {
        return title;
    }

    public String hint() {
        return hint;
    }
}
