package com.dierks.craftbridge.recipes;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One admin-defined crafting recipe as stored in {@code recipes.yml}. Shaped recipes
 * keep a trimmed shape + legend; shapeless ones keep a flat ingredient list.
 */
public final class CustomRecipe {

    public static final String NAMESPACE = "craftbridge";

    private final String id;
    private final boolean shaped;
    private final boolean enabled;
    private final String group;
    private final ItemStack result;
    /** Shaped: rows of letters. Empty for shapeless. */
    private final List<String> shape;
    /** Shaped: letter → ingredient. */
    private final Map<Character, Ingredient> legend;
    /** Shapeless: the ingredient list. Empty for shaped. */
    private final List<Ingredient> ingredients;

    public CustomRecipe(String id, boolean shaped, boolean enabled, String group, ItemStack result,
                        List<String> shape, Map<Character, Ingredient> legend, List<Ingredient> ingredients) {
        this.id = id;
        this.shaped = shaped;
        this.enabled = enabled;
        this.group = group == null ? "" : group;
        this.result = result.clone();
        this.shape = shape == null ? List.of() : List.copyOf(shape);
        this.legend = legend == null ? Map.of() : Map.copyOf(legend);
        this.ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }

    public String id() {
        return id;
    }

    public NamespacedKey key() {
        return new NamespacedKey(NAMESPACE, id);
    }

    public boolean shaped() {
        return shaped;
    }

    public boolean enabled() {
        return enabled;
    }

    public String group() {
        return group;
    }

    public ItemStack result() {
        return result.clone();
    }

    public List<String> shape() {
        return shape;
    }

    public Map<Character, Ingredient> legend() {
        return legend;
    }

    public List<Ingredient> ingredients() {
        return ingredients;
    }

    public CustomRecipe withEnabled(boolean value) {
        return new CustomRecipe(id, shaped, value, group, result, shape, legend, ingredients);
    }

    /** The recipe laid out on a 3x3 grid (row-major, null = empty) for the editor / lore. */
    public List<Ingredient> asGrid() {
        if (shaped) {
            return RecipeShape.toGrid(shape, legend);
        }
        List<Ingredient> grid = new ArrayList<>(Collections.nCopies(9, (Ingredient) null));
        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            grid.set(i, ingredients.get(i));
        }
        return grid;
    }

    /** All distinct ingredients with a letter, for lore legends (shapeless gets letters too). */
    public RecipeShape.Shape<Ingredient> shapeForDisplay() {
        return RecipeShape.of(asGrid(), Ingredient::sameAs);
    }

    public boolean isEmpty() {
        return shaped ? shape.isEmpty() || legend.isEmpty() : ingredients.isEmpty();
    }
}
