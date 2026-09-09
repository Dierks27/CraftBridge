package com.dierks.craftbridge.config;

import java.util.List;
import java.util.Set;

/**
 * Validates a crafting shape from config.yml <em>before</em> handing it to Bukkit. Free of
 * Bukkit types so it can be unit tested.
 *
 * <p>The point is the error message. Bukkit's {@code ShapedRecipe#shape} reports a bad shape
 * as whatever exception it happens to hit first — an empty list surfaces as
 * "Index 0 out of bounds for length 0", which tells an admin nothing about which key in
 * their config is wrong. An optional config block being unparseable must never leave a block
 * uncraftable, so a shape that does not pass here is reported by name and replaced with the
 * built-in one.
 */
public final class ShapeSpec {

    private ShapeSpec() {
    }

    /**
     * @param shape   the configured rows
     * @param symbols the ingredient letters that are defined for it
     * @return what is wrong with the shape, or null when it is usable
     */
    public static String problem(List<String> shape, Set<Character> symbols) {
        if (shape == null || shape.isEmpty()) {
            return "no rows: expected 1 to 3 rows like ['HEH', 'CBC', 'HRH']";
        }
        if (shape.size() > 3) {
            return "it has " + shape.size() + " rows: a crafting grid is at most 3";
        }
        int width = -1;
        boolean anySymbol = false;
        for (int i = 0; i < shape.size(); i++) {
            String row = shape.get(i);
            if (row == null) {
                return "row " + (i + 1) + " is empty: every row needs 1 to 3 characters";
            }
            if (row.isEmpty() || row.length() > 3) {
                return "row " + (i + 1) + " is '" + row + "': every row needs 1 to 3 characters";
            }
            if (width == -1) {
                width = row.length();
            } else if (row.length() != width) {
                return "row " + (i + 1) + " is '" + row + "' but row 1 is " + width
                        + " wide: every row must be the same width";
            }
            for (char c : row.toCharArray()) {
                if (c == ' ') {
                    continue;
                }
                anySymbol = true;
                if (symbols == null || !symbols.contains(c)) {
                    return "row " + (i + 1) + " uses '" + c + "', which has no entry under ingredients";
                }
            }
        }
        return anySymbol ? null : "it is all spaces: a recipe needs at least one ingredient";
    }
}
