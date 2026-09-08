package com.dierks.craftbridge.recipes.gui;

/**
 * Where everything sits in the recipe editor's 54-slot chest GUI, kept free of Bukkit types
 * so the one rule that matters can be unit tested: <b>no slot the admin is meant to fill
 * ever holds a decorative item</b>. A filler pane in an editable slot is a real item the
 * admin can pick up, which is both confusing and an infinite item source.
 *
 * <pre>
 *  row 0:  [c][g][g][g][c] [f][R][f] [type]
 *  row 1:  [c][g][g][g][c] [→][ ][+] [save]
 *  row 2:  [c][g][g][g][c] [f][f][-] [cancel]
 *  row 3:  [M][m][m][m][s]  .  .  .  [enabled]
 *  row 4:  [s][m][m][m][s]  .  .  .  [info]
 *  row 5:  [s][m][m][m][s]  .  .  .  [warning]
 * </pre>
 * {@code g} = editable grid slot, {@code [ ]} = the editable result slot, {@code c} = the
 * cyan frame around the grid, {@code f} = the orange frame around the result, {@code R} =
 * the "Result" label, {@code m} = the per-slot match-mode indicator, {@code M} = its label,
 * {@code s} = the indicator block's frame.
 */
public final class RecipeEditorLayout {

    public static final int SIZE = 54;

    /** The 3x3 crafting grid: editable. */
    public static final int[] GRID = {1, 2, 3, 10, 11, 12, 19, 20, 21};
    /** Match-mode indicator for the grid slot three rows above. */
    public static final int[] INDICATOR = {28, 29, 30, 37, 38, 39, 46, 47, 48};
    /** The result: editable. */
    public static final int RESULT = 15;

    public static final int ARROW = 14;
    public static final int RESULT_LABEL = 6;
    public static final int COUNT_UP = 16;
    public static final int COUNT_DOWN = 25;
    public static final int MATCH_LABEL = 27;

    public static final int TOGGLE_TYPE = 8;
    public static final int SAVE = 17;
    public static final int CANCEL = 26;
    public static final int TOGGLE_ENABLED = 35;
    public static final int INFO = 44;
    public static final int WARNING = 53;

    /** Cyan frame down both sides of the crafting grid. */
    public static final int[] GRID_FRAME = {0, 4, 9, 13, 18, 22};
    /** Orange frame around the result slot. */
    public static final int[] RESULT_FRAME = {5, 7, 23, 24};
    /** Muted frame around the match-mode block. */
    public static final int[] MATCH_FRAME = {31, 36, 40, 45, 49};

    private RecipeEditorLayout() {
    }

    /** True for a slot the admin puts items into: never give one of these a filler item. */
    public static boolean isEditable(int slot) {
        if (slot == RESULT) {
            return true;
        }
        for (int g : GRID) {
            if (g == slot) {
                return true;
            }
        }
        return false;
    }

    /** True for a slot this layout draws something decorative or clickable into. */
    public static boolean isDecorated(int slot) {
        if (isEditable(slot)) {
            return false;
        }
        for (int s : INDICATOR) {
            if (s == slot) {
                return true;
            }
        }
        for (int[] frame : new int[][]{GRID_FRAME, RESULT_FRAME, MATCH_FRAME}) {
            for (int s : frame) {
                if (s == slot) {
                    return true;
                }
            }
        }
        return slot == ARROW || slot == RESULT_LABEL || slot == COUNT_UP || slot == COUNT_DOWN
                || slot == MATCH_LABEL || slot == TOGGLE_TYPE || slot == SAVE || slot == CANCEL
                || slot == TOGGLE_ENABLED || slot == INFO || slot == WARNING;
    }
}
