package com.dierks.craftbridge.recipes;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeShapeTest {

    @Test
    void trimsEmptyRowsAndColumnsAndAssignsLetters() {
        // bottom-right 2x2: stick over plank, plank over stick
        List<String> grid = Arrays.asList(
                null, null, null,
                null, "stick", "plank",
                null, "plank", "stick");
        RecipeShape.Shape<String> shape = RecipeShape.of(grid, String::equals);
        assertEquals(List.of("AB", "BA"), shape.rows());
        assertEquals(Map.of('A', "stick", 'B', "plank"), shape.legend());
    }

    @Test
    void keepsInternalGaps() {
        List<String> grid = Arrays.asList(
                "x", null, "x",
                null, null, null,
                "x", null, "x");
        RecipeShape.Shape<String> shape = RecipeShape.of(grid, String::equals);
        assertEquals(List.of("A A", "   ", "A A"), shape.rows());
    }

    @Test
    void emptyGridGivesNoRows() {
        List<String> grid = Arrays.asList(new String[9]);
        RecipeShape.Shape<String> shape = RecipeShape.of(grid, String::equals);
        assertTrue(shape.rows().isEmpty());
        assertTrue(shape.legend().isEmpty());
    }

    @Test
    void roundTripsThroughGrid() {
        List<String> back = RecipeShape.toGrid(List.of(" G ", "GMG", " S "), Map.of('G', "glow", 'M', "magma", 'S', "stick"));
        assertEquals(Arrays.asList(null, "glow", null, "glow", "magma", "glow", null, "stick", null), back);
    }
}
