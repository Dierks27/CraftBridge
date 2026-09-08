package com.dierks.craftbridge.workbench;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "How useful is this item as an ingredient?", built once from the server's recipe list.
 *
 * <p>The Linked Workbench can only show as many storage item types as the player has empty
 * inventory slots, so page one has to be the page they want: types that are ingredients in
 * recipes the player has actually unlocked come first, most-used first. The per-recipe
 * ingredient sets are cached here; the per-player weighting is a sum over that player's
 * discovered recipes, taken once when they open the table.
 */
public final class RecipeIngredientIndex {

    private final Map<NamespacedKey, List<Material>> byRecipe;
    private final Map<Material, Integer> everyRecipe;

    private RecipeIngredientIndex(Map<NamespacedKey, List<Material>> byRecipe, Map<Material, Integer> everyRecipe) {
        this.byRecipe = byRecipe;
        this.everyRecipe = everyRecipe;
    }

    /** Walk every recipe the server has (vanilla, datapack and plugin-registered alike). */
    public static RecipeIngredientIndex build() {
        Map<NamespacedKey, List<Material>> byRecipe = new HashMap<>();
        Map<Material, Integer> everyRecipe = new HashMap<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe;
            try {
                recipe = it.next();
            } catch (RuntimeException ex) {
                continue; // a recipe type this server version cannot convert to the Bukkit API
            }
            if (!(recipe instanceof Keyed keyed)) {
                continue;
            }
            List<Material> ingredients = ingredientsOf(recipe);
            if (ingredients.isEmpty()) {
                continue;
            }
            byRecipe.put(keyed.getKey(), ingredients);
            for (Material m : new HashSet<>(ingredients)) {
                everyRecipe.merge(m, 1, Integer::sum);
            }
        }
        return new RecipeIngredientIndex(byRecipe, everyRecipe);
    }

    public int recipeCount() {
        return byRecipe.size();
    }

    /**
     * How many of the player's unlocked recipes use each material as an ingredient. Falls
     * back to "every recipe on the server" for a player who has discovered nothing yet, so
     * page one is still sorted by something useful.
     */
    public Map<Material, Integer> weights(Player player) {
        Set<NamespacedKey> discovered;
        try {
            discovered = player.getDiscoveredRecipes();
        } catch (RuntimeException ex) {
            return everyRecipe;
        }
        if (discovered == null || discovered.isEmpty()) {
            return everyRecipe;
        }
        Map<Material, Integer> weights = new HashMap<>();
        for (NamespacedKey key : discovered) {
            List<Material> ingredients = byRecipe.get(key);
            if (ingredients == null) {
                continue;
            }
            for (Material m : new HashSet<>(ingredients)) {
                weights.merge(m, 1, Integer::sum);
            }
        }
        return weights.isEmpty() ? everyRecipe : weights;
    }

    /** The materials that can go in this recipe's input slots. */
    private static List<Material> ingredientsOf(Recipe recipe) {
        List<Material> out = new ArrayList<>();
        try {
            if (recipe instanceof ShapedRecipe shaped) {
                for (RecipeChoice choice : shaped.getChoiceMap().values()) {
                    addChoice(out, choice);
                }
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                for (RecipeChoice choice : shapeless.getChoiceList()) {
                    addChoice(out, choice);
                }
            } else if (recipe instanceof CookingRecipe<?> cooking) {
                addChoice(out, cooking.getInputChoice());
            } else if (recipe instanceof StonecuttingRecipe cutting) {
                addChoice(out, cutting.getInputChoice());
            } else if (recipe instanceof SmithingRecipe smithing) {
                addChoice(out, smithing.getBase());
                addChoice(out, smithing.getAddition());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An API shape this build does not have: that recipe just does not contribute.
        }
        return out;
    }

    @SuppressWarnings("deprecation") // RecipeChoice#getItemStack is the only shape-agnostic accessor
    private static void addChoice(List<Material> out, RecipeChoice choice) {
        if (choice == null) {
            return;
        }
        try {
            if (choice instanceof RecipeChoice.MaterialChoice material) {
                out.addAll(material.getChoices());
                return;
            }
            if (choice instanceof RecipeChoice.ExactChoice exact) {
                for (ItemStack stack : exact.getChoices()) {
                    if (stack != null) {
                        out.add(stack.getType());
                    }
                }
                return;
            }
            // Any other RecipeChoice (Paper's item-type and predicate choices): one
            // representative stack is enough to know which item it wants.
            ItemStack representative = choice.getItemStack();
            if (representative != null) {
                out.add(representative.getType());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Same as above: skip this choice rather than lose the whole index.
        }
    }
}
