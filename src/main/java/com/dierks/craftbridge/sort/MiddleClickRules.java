package com.dierks.craftbridge.sort;

/**
 * What a middle-click from the CraftBridge-Client mod does, as a table: which screen is open,
 * which half was clicked, and what the player is holding decide it. Bukkit-free so every cell
 * is a unit test; {@link SortFeature#sortFromClient} gathers the inputs and carries it out.
 */
public final class MiddleClickRules {

    /** What the player has open, as far as sorting cares. */
    public enum Screen {
        /** A chest, double chest, barrel or shulker box. */
        SORTABLE_CONTAINER,
        /** Their own inventory screen (Bukkit's CRAFTING view). */
        OWN_INVENTORY,
        /** One of CraftBridge's own menus, the Combo Chest included: never sorted. */
        CRAFTBRIDGE_MENU,
        /** Anything else: a furnace, a Linked Workbench, another plugin's GUI. */
        OTHER
    }

    public enum Outcome {
        /** Sort the container (and the player's rows, if they opted in). */
        SORT_CONTAINER,
        /** Sort just the player's main rows. */
        SORT_PLAYER,
        /** Do nothing and say nothing: middle-clicking out of habit is not an error. */
        SILENT,
        /** Refuse and say so: inventory sorting is off on this server. */
        INVENTORY_SORTING_OFF
    }

    private MiddleClickRules() {
    }

    /**
     * @param playerSide       the click was over the player's own slots
     * @param cursorEmpty      nothing on the cursor; a click carrying a stack is a place, not a sort
     * @param inventoryAllowed {@code sorting.player-inventory.allowed}
     */
    public static Outcome decide(Screen screen, boolean playerSide, boolean cursorEmpty, boolean inventoryAllowed) {
        if (!cursorEmpty || screen == Screen.CRAFTBRIDGE_MENU || screen == Screen.OTHER) {
            return Outcome.SILENT;
        }
        if (playerSide) {
            return inventoryAllowed ? Outcome.SORT_PLAYER : Outcome.INVENTORY_SORTING_OFF;
        }
        return screen == Screen.SORTABLE_CONTAINER ? Outcome.SORT_CONTAINER : Outcome.SILENT;
    }
}
