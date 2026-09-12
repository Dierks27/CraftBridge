package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.items.CustomItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
    private final CustomItemRegistry customItems;
    private final Set<NamespacedKey> registered = new HashSet<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public RecipeRegistry(CraftBridgePlugin plugin, CustomItemRegistry customItems) {
        this.plugin = plugin;
        this.customItems = customItems;
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
        if (recipe.isCooking()) {
            return toCooking(recipe);
        }
        if (recipe.shaped()) {
            ShapedRecipe shaped = new ShapedRecipe(recipe.key(), recipe.result());
            shaped.shape(recipe.shape().toArray(new String[0]));
            for (Map.Entry<Character, Ingredient> e : recipe.legend().entrySet()) {
                if (usesLetter(recipe.shape(), e.getKey())) {
                    shaped.setIngredient(e.getKey(), e.getValue().toChoice(customItems));
                }
            }
            if (!recipe.group().isEmpty()) {
                shaped.setGroup(recipe.group());
            }
            return shaped;
        }
        ShapelessRecipe shapeless = new ShapelessRecipe(recipe.key(), recipe.result());
        for (Ingredient ing : recipe.ingredients()) {
            shapeless.addIngredient(ing.toChoice(customItems));
        }
        if (!recipe.group().isEmpty()) {
            shapeless.setGroup(recipe.group());
        }
        return shapeless;
    }

    /**
     * One {@link CookingRecipe} for the cooker the recipe's kind names. Each cooking kind is
     * its own recipe: vanilla datapacks model smelting, smoking, blasting and campfire
     * cooking separately, and registering one entry into several cookers would hide which
     * one the admin meant.
     */
    private Recipe toCooking(CustomRecipe recipe) {
        Ingredient input = recipe.input();
        if (input == null) {
            throw new IllegalArgumentException("cooking recipe has no ingredient");
        }
        RecipeChoice choice = input.toChoice(customItems);
        NamespacedKey key = recipe.key();
        ItemStack result = recipe.result();
        int time = recipe.cookingTime();
        float xp = recipe.experience();
        CookingRecipe<?> cooking = switch (recipe.kind()) {
            case FURNACE -> new FurnaceRecipe(key, result, choice, xp, time);
            case SMOKER -> new SmokingRecipe(key, result, choice, xp, time);
            case BLAST_FURNACE -> new BlastingRecipe(key, result, choice, xp, time);
            case CAMPFIRE -> new CampfireRecipe(key, result, choice, xp, time);
            default -> throw new IllegalStateException("not a cooking kind: " + recipe.kind());
        };
        if (!recipe.group().isEmpty()) {
            cooking.setGroup(recipe.group());
        }
        return cooking;
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
            matrix[i] = ing == null ? null : ing.display(customItems);
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

    /**
     * The vanilla cooking recipe this one would be shadowed by, or null when there is none.
     *
     * <p>This is not the same warning as {@link #conflictFor}, and it matters more than it
     * looks. Furnaces, smokers and blast furnaces do not resolve their recipe through the
     * lookup CraftBukkit patches to give plugin recipes priority — they go through
     * {@code RecipeManager.CachedCheck}, which returns the block entity's remembered last
     * recipe as soon as it still matches. Vanilla cooking ingredients match on item type, so
     * a furnace that last smelted the plain base item will keep using the <em>vanilla</em>
     * recipe when a stamped custom item is put in: vanilla output, vanilla experience,
     * vanilla cook time, and this recipe's choice never consulted. The cache is per block and
     * in memory only, so it clears on chunk unload — which makes the failure intermittent and
     * very hard to diagnose from a bug report.
     *
     * <p>Campfires have a matching split: placement uses the patched lookup (so this recipe
     * gates what may be placed) but {@code cookTick} resolves the output through the cache.
     *
     * <p>There is nothing the plugin can do about it from the API side, so the editor warns
     * instead. A recipe whose input material has no vanilla recipe for that cooker — rotten
     * flesh in a furnace, for instance — is unaffected.
     */
    public Recipe vanillaCookingShadow(CustomRecipe recipe) {
        if (!recipe.isCooking() || recipe.input() == null) {
            return null;
        }
        ItemStack sample = recipe.input().display(customItems);
        if (sample == null || sample.getType() == Material.AIR) {
            return null;
        }
        // The plain, unstamped item is what a vanilla recipe would have matched and cached.
        ItemStack plain = new ItemStack(sample.getType());
        Class<?> wanted = cookingClassOf(recipe.kind());
        NamespacedKey self = recipe.key();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe other;
            try {
                other = it.next();
            } catch (RuntimeException ex) {
                continue;
            }
            if (!wanted.isInstance(other) || !(other instanceof CookingRecipe<?> cooking)) {
                continue;
            }
            if (cooking.getKey().equals(self)) {
                continue;
            }
            if (CustomRecipe.NAMESPACE.equals(cooking.getKey().getNamespace())) {
                continue; // another CraftBridge recipe is a conflict, not a vanilla shadow
            }
            try {
                if (cooking.getInputChoice().test(plain)) {
                    return cooking;
                }
            } catch (RuntimeException ignored) {
                // A choice that cannot be tested is not evidence of a shadow.
            }
        }
        return null;
    }

    private static Class<?> cookingClassOf(RecipeKind kind) {
        return switch (kind) {
            case FURNACE -> FurnaceRecipe.class;
            case SMOKER -> SmokingRecipe.class;
            case BLAST_FURNACE -> BlastingRecipe.class;
            case CAMPFIRE -> CampfireRecipe.class;
            default -> CookingRecipe.class;
        };
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
                    + "(or an admin runs /jeiproxy handshake <player>) because features.jei-recipe-sync is off.");
        }
    }

    public void unlockFor(Player player) {
        if (!registered.isEmpty()) {
            player.discoverRecipes(registered);
        }
    }
}
