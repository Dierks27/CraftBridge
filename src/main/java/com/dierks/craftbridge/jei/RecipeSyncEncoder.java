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

    record Encoded(byte[] bytes, int recipeCount) {
    }
}
