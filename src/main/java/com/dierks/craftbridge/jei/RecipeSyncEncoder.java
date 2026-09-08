package com.dierks.craftbridge.jei;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * The server-internals side of recipe sync, kept behind an interface so the Bukkit-only
 * feature never references NMS classes directly (and can report a clean failure if the
 * implementation does not load on a newer Paper build).
 */
public interface RecipeSyncEncoder {

    /**
     * Encode the server's recipes as Fabric's {@code fabric:recipe_sync} payload.
     *
     * @param recipeTypeIds recipe type ids to include (e.g. {@code minecraft:crafting}), or null for all
     * @return the payload bytes and how many recipes it holds
     */
    Encoded encode(Set<String> recipeTypeIds);

    /** Re-send the vanilla recipe data to one player so JEI restarts with the synced recipes. */
    void resendRecipes(Player player);

    /**
     * How many recipes the server currently has, of every type. Cheap: used to notice that
     * some other plugin registered or removed a recipe and the snapshot is stale.
     */
    int liveRecipeCount();

    /**
     * @param bytes       the payload
     * @param recipeCount how many recipes it holds
     * @param byNamespace how many of them came from each namespace ({@code minecraft},
     *                    {@code craftbridge}, other plugins) — the quick check that
     *                    plugin-registered recipes really are in the payload
     */
    record Encoded(byte[] bytes, int recipeCount, java.util.Map<String, Integer> byNamespace) {
    }
}
