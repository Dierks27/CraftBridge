package com.dierks.craftbridge.recipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Turns a 3x3 grid (row-major, {@code null} = empty) into a vanilla-style shape: the
 * empty rows/columns around the used cells are trimmed (so a 2x2 recipe placed anywhere
 * in the editor matches anywhere in a crafting table), and each distinct ingredient is
 * given a letter. Pure Java so it can be unit-tested.
 */
public final class RecipeShape {

    public static final String LETTERS = "ABCDEFGHI";

    /** Trimmed shape rows plus the legend letter → ingredient. */
    public record Shape<T>(List<String> rows, Map<Character, T> legend) {
        public int width() {
            return rows.isEmpty() ? 0 : rows.getFirst().length();
        }

        public int height() {
            return rows.size();
        }
    }

    private RecipeShape() {
    }

    /**
     * @param grid  9 cells, row-major; null = empty
     * @param same  decides when two cells hold the same ingredient (share a letter)
     * @return the shape, or a shape with no rows if the grid is empty
     */
    public static <T> Shape<T> of(List<T> grid, BiPredicate<T, T> same) {
        if (grid.size() != 9) {
            throw new IllegalArgumentException("grid must have 9 cells");
        }
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int i = 0; i < 9; i++) {
            if (grid.get(i) != null) {
                int r = i / 3, c = i % 3;
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                minC = Math.min(minC, c);
                maxC = Math.max(maxC, c);
            }
        }
        if (maxR < 0) {
            return new Shape<>(List.of(), Map.of());
        }
        Map<Character, T> legend = new LinkedHashMap<>();
        List<T> distinct = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (int r = minR; r <= maxR; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = minC; c <= maxC; c++) {
                T cell = grid.get(r * 3 + c);
                if (cell == null) {
                    row.append(' ');
                    continue;
                }
                int idx = -1;
                for (int i = 0; i < distinct.size(); i++) {
                    if (same.test(distinct.get(i), cell)) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    idx = distinct.size();
                    distinct.add(cell);
                    legend.put(LETTERS.charAt(idx), cell);
                }
                row.append(LETTERS.charAt(idx));
            }
            rows.add(row.toString());
        }
        return new Shape<>(rows, legend);
    }

    /** Expand shape rows back into a 9-cell grid (top-left aligned), for display and matching. */
    public static <T> List<T> toGrid(List<String> rows, Map<Character, T> legend) {
        List<T> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(null);
        }
        for (int r = 0; r < rows.size() && r < 3; r++) {
            String row = rows.get(r);
            for (int c = 0; c < row.length() && c < 3; c++) {
                char ch = row.charAt(c);
                if (ch != ' ') {
                    grid.set(r * 3 + c, legend.get(ch));
                }
            }
        }
        return grid;
    }
}
