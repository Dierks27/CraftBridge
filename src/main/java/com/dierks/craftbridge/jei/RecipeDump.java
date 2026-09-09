package com.dierks.craftbridge.jei;

import com.dierks.craftbridge.util.Items;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Describes a recipe the way the wire cares about: what each ingredient slot will accept and
 * what the result carries. Printing the live recipe next to the same recipe after an
 * encode/decode round trip is how {@code /craftbridge jei dump} answers "what does this
 * recipe look like by the time JEI sees it?" — in particular whether an
 * {@link RecipeChoice.ExactChoice} arrives with its stacks, as a plain item type, or empty.
 */
public final class RecipeDump {

    private RecipeDump() {
    }

    public static List<String> describe(String label, Recipe recipe) {
        List<String> out = new ArrayList<>();
        if (recipe == null) {
            out.add(label + ": (none)");
            return out;
        }
        out.add(label + ": " + recipe.getClass().getSimpleName());
        out.add("  result: " + stack(recipe.getResult()));
        if (recipe instanceof ShapedRecipe shaped) {
            out.add("  shape: " + String.join(" / ", shaped.getShape()));
            for (Map.Entry<Character, RecipeChoice> e : shaped.getChoiceMap().entrySet()) {
                out.add("  '" + e.getKey() + "' -> " + choice(e.getValue()));
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            int i = 1;
            for (RecipeChoice c : shapeless.getChoiceList()) {
                out.add("  #" + (i++) + " -> " + choice(c));
            }
        }
        return out;
    }

    private static String choice(RecipeChoice choice) {
        if (choice == null) {
            return "(null)";
        }
        String kind = choice.getClass().getSimpleName();
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            List<String> stacks = new ArrayList<>();
            for (ItemStack s : exact.getChoices()) {
                stacks.add(stack(s));
            }
            return kind + " exact=" + stacks.size() + " " + stacks;
        }
        if (choice instanceof RecipeChoice.MaterialChoice material) {
            return kind + " " + material.getChoices();
        }
        try {
            @SuppressWarnings("deprecation")
            ItemStack representative = choice.getItemStack();
            return kind + " ~ " + stack(representative);
        } catch (RuntimeException | LinkageError ex) {
            return kind + " (cannot describe: " + ex + ")";
        }
    }

    /** Material, amount, and the two things that make an item "custom": a name and PDC data. */
    private static String stack(ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(stack.getAmount()).append('x').append(stack.getType());
        if (stack.hasItemMeta() && stack.getItemMeta() != null) {
            var meta = stack.getItemMeta();
            if (meta.hasDisplayName()) {
                sb.append(" named'").append(Items.describe(stack)).append('\'');
            }
            int pdc = meta.getPersistentDataContainer().getKeys().size();
            if (pdc > 0) {
                sb.append(" pdc=").append(meta.getPersistentDataContainer().getKeys());
            }
        }
        return sb.toString();
    }
}
