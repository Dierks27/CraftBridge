package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.items.CustomItemRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One admin-defined recipe as stored in {@code recipes.yml}.
 *
 * <p>Shaped recipes keep a trimmed shape + legend; shapeless ones keep a flat ingredient
 * list; the four cooking kinds keep a single ingredient in {@link #ingredients()} plus a
 * cook time and an experience reward.
 */
public final class CustomRecipe {

    public static final String NAMESPACE = "craftbridge";

    private final String id;
    private final RecipeKind kind;
    private final boolean enabled;
    private final String group;
    private final ItemStack result;
    /** Shaped: rows of letters. Empty for every other kind. */
    private final List<String> shape;
    /** Shaped: letter → ingredient. */
    private final Map<Character, Ingredient> legend;
    /** Shapeless: the ingredient list. Cooking: exactly one entry. Empty for shaped. */
    private final List<Ingredient> ingredients;
    /** Cooking only: ticks in the block. Ignored for crafting kinds. */
    private final int cookingTime;
    /** Cooking only: experience dropped when the output is collected. */
    private final float experience;

    public CustomRecipe(String id, RecipeKind kind, boolean enabled, String group, ItemStack result,
                        List<String> shape, Map<Character, Ingredient> legend, List<Ingredient> ingredients,
                        int cookingTime, float experience) {
        this.id = id;
        this.kind = kind == null ? RecipeKind.SHAPED : kind;
        this.enabled = enabled;
        this.group = group == null ? "" : group;
        this.result = result.clone();
        this.shape = shape == null ? List.of() : List.copyOf(shape);
        this.legend = legend == null ? Map.of() : Map.copyOf(legend);
        this.ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        this.cookingTime = RecipeKind.clampCookingTime(
                cookingTime > 0 ? cookingTime : this.kind.defaultCookingTime());
        this.experience = RecipeKind.clampExperience(experience);
    }

    /** Crafting convenience constructor: no cook time or experience to supply. */
    public CustomRecipe(String id, RecipeKind kind, boolean enabled, String group, ItemStack result,
                        List<String> shape, Map<Character, Ingredient> legend, List<Ingredient> ingredients) {
        this(id, kind, enabled, group, result, shape, legend, ingredients,
                kind == null ? 0 : kind.defaultCookingTime(), RecipeKind.DEFAULT_EXPERIENCE);
    }

    /** A cooking recipe: one input, a cook time and an experience reward. */
    public static CustomRecipe cooking(String id, RecipeKind kind, boolean enabled, String group,
                                       ItemStack result, Ingredient input, int cookingTime, float experience) {
        return new CustomRecipe(id, kind, enabled, group, result, List.of(), Map.of(),
                List.of(input), cookingTime, experience);
    }

    public String id() {
        return id;
    }

    public NamespacedKey key() {
        return new NamespacedKey(NAMESPACE, id);
    }

    public RecipeKind kind() {
        return kind;
    }

    public boolean shaped() {
        return kind == RecipeKind.SHAPED;
    }

    public boolean isCooking() {
        return kind.isCooking();
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

    public int cookingTime() {
        return cookingTime;
    }

    public float experience() {
        return experience;
    }

    /** Cooking only: the single input, or null when the recipe has none yet. */
    public Ingredient input() {
        return ingredients.isEmpty() ? null : ingredients.get(0);
    }

    public CustomRecipe withEnabled(boolean value) {
        return new CustomRecipe(id, kind, value, group, result, shape, legend, ingredients, cookingTime, experience);
    }

    /**
     * The recipe laid out on a 3x3 grid (row-major, null = empty) for the editor / lore.
     * A cooking recipe occupies the single top-left cell, which is the slot the editor shows.
     */
    public List<Ingredient> asGrid() {
        if (shaped()) {
            return RecipeShape.toGrid(shape, legend);
        }
        List<Ingredient> grid = new ArrayList<>(Collections.nCopies(9, (Ingredient) null));
        int limit = Math.min(ingredients.size(), kind.inputSlots());
        for (int i = 0; i < limit; i++) {
            grid.set(i, ingredients.get(i));
        }
        return grid;
    }

    /** All distinct ingredients with a letter, for lore legends (shapeless gets letters too). */
    public RecipeShape.Shape<Ingredient> shapeForDisplay() {
        return RecipeShape.of(asGrid(), Ingredient::sameAs);
    }

    public boolean isEmpty() {
        if (shaped()) {
            return shape.isEmpty() || legend.isEmpty();
        }
        return ingredients.isEmpty();
    }

    /** Every ingredient this recipe uses, whatever its kind. */
    public List<Ingredient> allIngredients() {
        if (shaped()) {
            return List.copyOf(legend.values());
        }
        return ingredients;
    }

    /** The custom-item ids this recipe references (inputs and result), for orphan checks. */
    public List<String> customItemIds() {
        List<String> out = new ArrayList<>();
        for (Ingredient ing : allIngredients()) {
            if (ing != null && ing.isCustom()) {
                out.add(ing.customId());
            }
        }
        String resultId = CustomItemRegistry.idOf(result);
        if (resultId != null) {
            out.add(resultId);
        }
        return out;
    }
}
