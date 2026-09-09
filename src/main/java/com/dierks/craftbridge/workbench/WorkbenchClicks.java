package com.dierks.craftbridge.workbench;

/**
 * What a click in an open Linked Workbench should do. Kept free of Bukkit types so every
 * combination can be unit tested — this decision is the part of the workbench that has
 * broken twice, and a table you can assert against is the way it stops breaking.
 *
 * <p>The one rule that matters: <b>a phantom slot is a genuinely empty inventory slot</b>.
 * Only <em>taking</em> from one is special. Putting something into one is an ordinary
 * vanilla place, and refusing it is what made it impossible to move your own items around
 * while phantoms were on screen.
 */
public final class WorkbenchClicks {

    /** What the clicked slot is showing. */
    public enum Slot {
        /** A slot CraftBridge drew a storage item into. */
        PHANTOM,
        /** A slot CraftBridge drew a page button into. */
        BUTTON,
        /** Anything else: the player's real items, the crafting grid, the result. */
        REAL
    }

    public enum Action {
        /** Vanilla behaviour, untouched. */
        ALLOW,
        /** Refuse and re-send the slot exactly as it was. */
        CANCEL,
        /** Pull one stack of this type out of storage into this slot. */
        PULL_ONE,
        /** Pull half a stack. */
        PULL_HALF,
        /** Pull as much as the player's free slots and storage allow. */
        PULL_ALL,
        /** Turn the storage page. */
        PAGE
    }

    private WorkbenchClicks() {
    }

    /**
     * @param slot        what the clicked raw slot is showing
     * @param cursorEmpty whether the player's cursor is empty (a click with something on the
     *                    cursor is a <em>place</em>, never a take)
     * @param clickType   Bukkit's {@code ClickType} name, taken as a string so this class
     *                    stays Bukkit-free
     */
    public static Action decide(Slot slot, boolean cursorEmpty, String clickType) {
        if (slot == Slot.REAL) {
            return Action.ALLOW; // the player's own slots and the grid are never touched
        }
        if (!cursorEmpty) {
            // Holding something: this is putting an item down into a slot that really is
            // empty. Vanilla handles it; the phantom just moves elsewhere on the rebuild.
            return Action.ALLOW;
        }
        if (slot == Slot.BUTTON) {
            return isPlainClick(clickType) ? Action.PAGE : Action.CANCEL;
        }
        return switch (clickType == null ? "" : clickType) {
            case "LEFT", "WINDOW_BORDER_LEFT" -> Action.PULL_ONE;
            case "RIGHT", "WINDOW_BORDER_RIGHT" -> Action.PULL_HALF;
            case "SHIFT_LEFT", "SHIFT_RIGHT" -> Action.PULL_ALL;
            // Number keys, drops, double-click collect and offhand swaps have no sensible
            // meaning on a slot whose contents are not really there.
            default -> Action.CANCEL;
        };
    }

    private static boolean isPlainClick(String clickType) {
        return "LEFT".equals(clickType) || "RIGHT".equals(clickType)
                || "SHIFT_LEFT".equals(clickType) || "SHIFT_RIGHT".equals(clickType);
    }
}
