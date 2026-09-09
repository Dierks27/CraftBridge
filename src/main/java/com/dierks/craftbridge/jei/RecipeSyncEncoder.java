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
     * Encode one recipe exactly as the payload would, decode it straight back, and hand back
     * the result as a Bukkit recipe: what a client actually receives, in terms that can be
     * printed without touching server internals. Used by {@code /craftbridge jei dump} to
     * answer "what happens to this recipe on the wire?" with evidence rather than theory.
     */
    RoundTrip roundTrip(String recipeKey);

    /**
     * @param bytes   how many bytes the recipe took on the wire, or -1 if it was not encoded
     * @param decoded the recipe as it comes back off the wire, or null when that failed
     * @param error   why it failed, or null
     */
    record RoundTrip(int bytes, org.bukkit.inventory.Recipe decoded, String error) {
    }

    /**
     * @param bytes       the payload
     * @param recipeCount how many recipes it holds
     * @param byNamespace how many of them came from each namespace ({@code minecraft},
     *                    {@code craftbridge}, other plugins) — the quick check that
     *                    plugin-registered recipes really are in the payload
     * @param problems    recipes left out because they would not survive the wire, one line
     *                    each. A client decodes the payload in a single pass and aborts the
     *                    <em>whole</em> thing on the first bad recipe, so one that cannot be
     *                    encoded safely has to be dropped here — loudly, never silently.
     */
    record Encoded(byte[] bytes, int recipeCount, java.util.Map<String, Integer> byNamespace,
                   java.util.List<String> problems) {
    }
}
