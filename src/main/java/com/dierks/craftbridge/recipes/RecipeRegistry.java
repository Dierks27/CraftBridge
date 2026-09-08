package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers {@link CustomRecipe}s with the server as real vanilla-style recipes under
 * {@code craftbridge:<id>}, and pushes changes to online clients.
 *
 * <p>After any change: {@code Bukkit.updateRecipes()} re-sends the recipe data to every
 * client and every online player discovers the recipe in their recipe book. JEI clients
 * get the new recipe through CraftBridge's own recipe sync (a later PR); until then the
 * console line printed by {@link #notifyClients} says how to refresh them.
 */
public final class RecipeRegistry {

    private final CraftBridgePlugin plugin;
    private final Set<NamespacedKey> registered = new HashSet<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public RecipeRegistry(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Called after the registered recipe set changes (used by the JEI recipe sync). */
    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    public Set<NamespacedKey> registeredKeys() {
        return Set.copyOf(registered);
    }

    /** (Re)register every enabled recipe in the store; disabled ones are removed. */
    public int registerAll(Map<String, CustomRecipe> recipes) {
        unregisterAll();
        int count = 0;
        for (CustomRecipe recipe : recipes.values()) {
            if (recipe.enabled() && register(recipe, false)) {
                count++;
            }
        }
        notifyClients();
        return count;
    }

    public void unregisterAll() {
        for (NamespacedKey key : new ArrayList<>(registered)) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    /** Register (replacing any previous version). Returns false if the server rejected it. */
    public boolean register(CustomRecipe recipe, boolean notify) {
        NamespacedKey key = recipe.key();
        Bukkit.removeRecipe(key);
        registered.remove(key);
        if (!recipe.enabled()) {
            if (notify) {
                notifyClients();
            }
            return false;
        }
        try {
            Recipe bukkit = toBukkit(recipe);
            if (!Bukkit.addRecipe(bukkit)) {
                plugin.getLogger().warning("Recipe '" + recipe.id() + "' was rejected by the server.");
                return false;
            }
            registered.add(key);
            if (notify) {
                notifyClients();
            }
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Recipe '" + recipe.id() + "' is invalid: " + ex.getMessage());
            return false;
        }
    }

    public void unregister(String id, boolean notify) {
        NamespacedKey key = new NamespacedKey(CustomRecipe.NAMESPACE, id);
        Bukkit.removeRecipe(key);
        registered.remove(key);
        if (notify) {
            notifyClients();
        }
    }

    private Recipe toBukkit(CustomRecipe recipe) {
        if (recipe.shaped()) {
            ShapedRecipe shaped = new ShapedRecipe(recipe.key(), recipe.result());
            shaped.shape(recipe.shape().toArray(new String[0]));
            for (Map.Entry<Character, Ingredient> e : recipe.legend().entrySet()) {
                if (usesLetter(recipe.shape(), e.getKey())) {
                    shaped.setIngredient(e.getKey(), e.getValue().toChoice());
                }
            }
            if (!recipe.group().isEmpty()) {
                shaped.setGroup(recipe.group());
            }
            return shaped;
        }
        ShapelessRecipe shapeless = new ShapelessRecipe(recipe.key(), recipe.result());
        for (Ingredient ing : recipe.ingredients()) {
            shapeless.addIngredient(ing.toChoice());
        }
        if (!recipe.group().isEmpty()) {
            shapeless.setGroup(recipe.group());
        }
        return shapeless;
    }

    private static boolean usesLetter(List<String> shape, char letter) {
        for (String row : shape) {
            if (row.indexOf(letter) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The recipe the server would craft from this 3x3 layout, if it is not {@code self}.
     * Used by the editor's "this overlaps an existing recipe" warning.
     */
    public Recipe conflictFor(List<Ingredient> grid, World world, String selfId) {
        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            Ingredient ing = grid.get(i);
            matrix[i] = ing == null ? null : ing.display();
        }
        Recipe existing;
        try {
            existing = Bukkit.getCraftingRecipe(matrix, world);
        } catch (RuntimeException ex) {
            return null;
        }
        if (existing == null) {
            return null;
        }
        if (selfId != null && existing instanceof Keyed keyed
                && keyed.getKey().equals(new NamespacedKey(CustomRecipe.NAMESPACE, selfId))) {
            return null;
        }
        return existing;
    }

    /** Push the current recipe set to connected clients and unlock ours in their recipe books. */
    public void notifyClients() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Recipe change listener failed: " + ex);
            }
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        try {
            Bukkit.updateRecipes();
        } catch (Throwable t) {
            plugin.getLogger().warning("Bukkit.updateRecipes() failed (" + t + "); clients see changes on relog.");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            unlockFor(p);
        }
        if (changeListeners.isEmpty()) {
            plugin.getLogger().info("Recipes changed. Vanilla clients are up to date; JEI clients need to rejoin "
                    + "(or an admin runs /jeiproxy handshake <player>) until CraftBridge's JEI recipe sync is enabled.");
        }
    }

    public void unlockFor(Player player) {
        if (!registered.isEmpty()) {
            player.discoverRecipes(registered);
        }
    }
}
