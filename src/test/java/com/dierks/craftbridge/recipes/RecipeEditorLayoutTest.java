package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.recipes.gui.RecipeEditorLayout;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's layout invariant: a slot the admin fills is never also a slot the GUI draws
 * something into. That is what made the New Recipe screen hand out glass panes.
 */
class RecipeEditorLayoutTest {

    @Test
    void everyEditableSlotIsFreeOfDecoration() {
        for (int slot = 0; slot < RecipeEditorLayout.SIZE; slot++) {
            if (RecipeEditorLayout.isEditable(slot)) {
                assertFalse(RecipeEditorLayout.isDecorated(slot),
                        "slot " + slot + " is editable and must never hold a GUI item");
            }
        }
    }

    @Test
    void theGridAndResultAreTheOnlyEditableSlots() {
        Set<Integer> editable = new HashSet<>();
        for (int slot = 0; slot < RecipeEditorLayout.SIZE; slot++) {
            if (RecipeEditorLayout.isEditable(slot)) {
                editable.add(slot);
            }
        }
        assertEquals(10, editable.size());
        for (int g : RecipeEditorLayout.GRID) {
            assertTrue(editable.contains(g));
        }
        assertTrue(editable.contains(RecipeEditorLayout.RESULT));
    }

    @Test
    void noSlotIsUsedTwice() {
        Set<Integer> seen = new HashSet<>();
        for (int[] block : new int[][]{RecipeEditorLayout.GRID, RecipeEditorLayout.INDICATOR,
                RecipeEditorLayout.GRID_FRAME, RecipeEditorLayout.RESULT_FRAME, RecipeEditorLayout.MATCH_FRAME}) {
            for (int slot : block) {
                assertTrue(seen.add(slot), "slot " + slot + " is claimed twice");
            }
        }
        for (int slot : new int[]{RecipeEditorLayout.RESULT, RecipeEditorLayout.ARROW,
                RecipeEditorLayout.RESULT_LABEL, RecipeEditorLayout.COUNT_UP, RecipeEditorLayout.COUNT_DOWN,
                RecipeEditorLayout.MATCH_LABEL, RecipeEditorLayout.TOGGLE_TYPE, RecipeEditorLayout.SAVE,
                RecipeEditorLayout.CANCEL, RecipeEditorLayout.TOGGLE_ENABLED, RecipeEditorLayout.INFO,
                RecipeEditorLayout.WARNING}) {
            assertTrue(seen.add(slot), "slot " + slot + " is claimed twice");
        }
    }

    @Test
    void everySlotIsInsideTheChest() {
        for (int slot = 0; slot < RecipeEditorLayout.SIZE; slot++) {
            // isDecorated/isEditable must not throw and must not both be true
            assertFalse(RecipeEditorLayout.isEditable(slot) && RecipeEditorLayout.isDecorated(slot));
        }
        for (int g : RecipeEditorLayout.GRID) {
            assertTrue(g >= 0 && g < RecipeEditorLayout.SIZE);
        }
        for (int i : RecipeEditorLayout.INDICATOR) {
            assertTrue(i >= 0 && i < RecipeEditorLayout.SIZE);
        }
    }

    @Test
    void eachIndicatorSitsUnderItsGridSlot() {
        for (int i = 0; i < 9; i++) {
            assertEquals(RecipeEditorLayout.GRID[i] + 27, RecipeEditorLayout.INDICATOR[i]);
        }
    }
}
