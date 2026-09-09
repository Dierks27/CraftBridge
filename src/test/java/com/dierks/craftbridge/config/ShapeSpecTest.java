package com.dierks.craftbridge.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bad shape must be reported by name, never as an index-out-of-bounds from deep in Bukkit. */
class ShapeSpecTest {

    private static final Set<Character> SYMBOLS = Set.of('H', 'E', 'C', 'B', 'R');

    @Test
    void theShippedComboChestShapeIsValid() {
        assertNull(ShapeSpec.problem(List.of("HEH", "CBC", "HRH"), SYMBOLS));
    }

    @Test
    void smallerGridsAreFine() {
        assertNull(ShapeSpec.problem(List.of("HH", "HH"), SYMBOLS));
        assertNull(ShapeSpec.problem(List.of("H"), SYMBOLS));
        assertNull(ShapeSpec.problem(List.of("H H", "  C"), SYMBOLS));
    }

    @Test
    void anEmptyShapeIsTheUpgradedConfigCaseAndSaysSo() {
        // Jeff's carried-over config: this is what surfaced as "Index 0 out of bounds for length 0".
        String problem = ShapeSpec.problem(List.of(), SYMBOLS);
        assertNotNull(problem);
        assertTrue(problem.contains("1 to 3 rows"), problem);
        assertNotNull(ShapeSpec.problem(null, SYMBOLS));
    }

    @Test
    void tooManyRowsOrTooWideIsNamed() {
        assertNotNull(ShapeSpec.problem(List.of("HHH", "HHH", "HHH", "HHH"), SYMBOLS));
        assertNotNull(ShapeSpec.problem(List.of("HHHH"), SYMBOLS));
    }

    @Test
    void raggedRowsAreNamedWithTheRow() {
        String problem = ShapeSpec.problem(List.of("HEH", "CB"), SYMBOLS);
        assertNotNull(problem);
        assertTrue(problem.contains("row 2"), problem);
    }

    @Test
    void aSymbolWithNoIngredientIsNamed() {
        String problem = ShapeSpec.problem(List.of("HEH", "CXC", "HRH"), SYMBOLS);
        assertNotNull(problem);
        assertTrue(problem.contains("'X'"), problem);
    }

    @Test
    void anAllSpaceShapeIsRejected() {
        assertNotNull(ShapeSpec.problem(List.of("   ", "   "), SYMBOLS));
    }

    @Test
    void nullRowsAreRejectedRatherThanThrowing() {
        assertNotNull(ShapeSpec.problem(Arrays.asList("HEH", null), SYMBOLS));
    }
}
