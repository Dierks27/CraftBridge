package com.dierks.craftbridge.gui;

import java.util.Map;

/**
 * What a click or drag in a CraftBridge menu means. Bukkit-free so every cell of the table
 * can be asserted in a test.
 *
 * <p>The rule this class exists to enforce: <b>a gesture that only puts items down is never
 * refused by default.</b> Twice now a blanket "cancel everything we did not explicitly build"
 * has broken an ordinary put-things-down gesture — first at the Linked Workbench (see
 * {@link com.dierks.craftbridge.workbench.WorkbenchClicks}), then in the Combo Chest, where
 * cancelling every click in the player's own inventory meant an item could not even be picked
 * up onto the cursor, so no cursor deposit or drag deposit could ever start. So refusals here
 * are enumerated one by one, with a reason, and {@link Action#VANILLA} is the fallback in the
 * player's own inventory.
 *
 * <p>There are only two gestures a menu has to refuse in the player's inventory, and both are
 * refused because they reach up into the menu rather than because of what they are: a
 * shift-click (which would shove items into button slots) and a double-click collect (which
 * would vacuum the menu's icons onto the cursor, minting items). Everything else — plain
 * clicks, right clicks, hotbar swaps, drops, drags that stay below — is the player moving
 * their own items around and is none of our business.
 */
public final class MenuClicks {

    /** Where the click landed. */
    public enum Region {
        /** A menu slot that is a button or decoration. */
        TOP_BUTTON,
        /** A menu slot the menu marked editable (the recipe editor's input slots). */
        TOP_EDITABLE,
        /** The player's own inventory, below the menu. */
        BOTTOM,
        /** Outside the window entirely (dropping what you are carrying). */
        OUTSIDE
    }

    public enum Action {
        /** Leave it to vanilla: the player is moving their own items. */
        VANILLA,
        /** Refuse, for one of the enumerated reasons. */
        CANCEL,
        /** Cancel and run the slot's click handler. */
        BUTTON,
        /** Empty hand on an empty editable slot: vanilla does nothing, so the menu gets it. */
        PICKER,
        /** Vanilla behaviour in an editable slot, then tell the menu the slot changed. */
        EDITABLE,
        /** Deposit the whole cursor stack. */
        DEPOSIT_ALL,
        /** Deposit one item off the cursor. */
        DEPOSIT_ONE,
        /** Deposit the clicked stack out of the player's inventory (shift-click). */
        DEPOSIT_SLOT
    }

    /** What a drag should do. */
    public enum Drag {
        /** Deposit the part of the drag aimed at the menu. */
        DEPOSIT,
        /** Let it happen. */
        ALLOW,
        /** Refuse: it would drop items into slots the menu owns. */
        CANCEL
    }

    /**
     * The menu's traits at the clicked slot.
     *
     * @param deposits true when the menu routes items dropped into it somewhere (the Combo Chest)
     * @param handler  true when the clicked slot has a click handler
     */
    public record Traits(boolean deposits, boolean handler) {
    }

    private MenuClicks() {
    }

    public static Action decide(Region region, String click, boolean cursorEmpty, boolean slotEmpty, Traits traits) {
        String type = click == null ? "" : click;
        return switch (region) {
            // Dropping what you are carrying is never the menu's business to refuse.
            case OUTSIDE -> Action.VANILLA;
            case BOTTOM -> inPlayerInventory(type, slotEmpty, traits);
            case TOP_EDITABLE -> inEditableSlot(type, cursorEmpty, slotEmpty, traits);
            case TOP_BUTTON -> inMenuSlot(type, cursorEmpty, traits);
        };
    }

    private static Action inPlayerInventory(String type, boolean slotEmpty, Traits traits) {
        if (isCollect(type)) {
            return Action.CANCEL; // would pull the menu's icons onto the cursor
        }
        if (isShift(type)) {
            // The one gesture that crosses into the menu. A deposit menu takes the stack;
            // anywhere else it would land in a button slot, so it is refused.
            return traits.deposits() && !slotEmpty ? Action.DEPOSIT_SLOT : Action.CANCEL;
        }
        return Action.VANILLA;
    }

    private static Action inMenuSlot(String type, boolean cursorEmpty, Traits traits) {
        if (!cursorEmpty && traits.deposits()) {
            if (isCollect(type)) {
                return Action.CANCEL; // a double-click with a full cursor collects, it does not place
            }
            return switch (type) {
                case "LEFT", "SHIFT_LEFT", "WINDOW_BORDER_LEFT" -> Action.DEPOSIT_ALL;
                case "RIGHT", "SHIFT_RIGHT", "WINDOW_BORDER_RIGHT" -> Action.DEPOSIT_ONE;
                // Middle-click clone, hotbar swap, offhand swap and drops are not deposits.
                default -> Action.CANCEL;
            };
        }
        return Action.BUTTON;
    }

    private static Action inEditableSlot(String type, boolean cursorEmpty, boolean slotEmpty, Traits traits) {
        if (cursorEmpty && slotEmpty && traits.handler()) {
            return Action.PICKER;
        }
        if (isShift(type) || isCollect(type)) {
            return Action.CANCEL; // a bulk move would spray items across the menu's buttons
        }
        return Action.EDITABLE;
    }

    /**
     * @param touchesMenu            the drag would put items in at least one menu slot
     * @param touchesNonEditableMenu at least one of those is a slot the menu owns
     */
    public static Drag decideDrag(boolean touchesMenu, boolean touchesNonEditableMenu, boolean deposits) {
        if (!touchesMenu) {
            return Drag.ALLOW; // entirely in the player's own inventory
        }
        if (deposits) {
            return Drag.DEPOSIT;
        }
        return touchesNonEditableMenu ? Drag.CANCEL : Drag.ALLOW;
    }

    /**
     * How much of a drag was aimed at the menu itself, given how many items each raw slot
     * would have gained. A drag that spans both inventories deposits only its share.
     */
    public static int dragIntoMenu(Map<Integer, Integer> addedByRawSlot, int menuSize) {
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : addedByRawSlot.entrySet()) {
            if (entry.getKey() < menuSize && entry.getValue() > 0) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /**
     * How many items stay on the cursor after offering some of it to the menu. Item loss and
     * item minting both live in this one sum, so it is stated once and asserted.
     *
     * @param offered how many were handed to the menu
     * @param refused how many of those it could not take (never more than were offered)
     */
    public static int keptOnCursor(int cursorAmount, int offered, int refused) {
        int taken = Math.max(0, Math.min(offered, cursorAmount)) - Math.max(0, Math.min(refused, offered));
        return Math.max(0, cursorAmount - taken);
    }

    private static boolean isShift(String type) {
        return "SHIFT_LEFT".equals(type) || "SHIFT_RIGHT".equals(type);
    }

    private static boolean isCollect(String type) {
        return "DOUBLE_CLICK".equals(type);
    }
}
