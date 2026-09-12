package com.dierks.craftbridge.items;

import java.util.Locale;
import java.util.Set;

/**
 * Id rules for custom items. Bukkit-free so the rules can be unit tested — the ids end up
 * inside a PersistentDataContainer value and in {@code custom-items.yml} keys, and a bad one
 * is only noticed much later, so it is worth pinning down.
 *
 * <p>Same character set as recipe ids ({@link com.dierks.craftbridge.recipes.RecipeStore}),
 * minus {@code /} and {@code .}: an item id is a flat name, not a path.
 */
public final class CustomItemIds {

    /** Ids that would collide with something the GUI or the yaml uses structurally. */
    private static final Set<String> RESERVED = Set.of("none", "null", "air", "");

    private CustomItemIds() {
    }

    /**
     * Null when the id is usable, otherwise the reason it is not (shown to the admin).
     *
     * <p>Checked against the id exactly as given, <em>not</em> a lowercased copy. The store
     * lowercases keys when it loads them, so accepting {@code BurnedFlesh} here would mean
     * writing an id that comes back as something else on the next restart, orphaning every
     * recipe that referenced it. Admin input is normalised by {@link #sanitise} before it
     * gets this far.
     */
    public static String problem(String id) {
        if (id == null || id.isBlank()) {
            return "an id is required";
        }
        if (RESERVED.contains(id.toLowerCase(Locale.ROOT))) {
            return "'" + id + "' is reserved";
        }
        if (id.length() > 64) {
            return "ids are at most 64 characters";
        }
        if (!id.matches("[a-z0-9_-]+")) {
            return "ids use lowercase letters, digits, _ and - only";
        }
        return null;
    }

    public static boolean isValid(String id) {
        return problem(id) == null;
    }

    /** Best-effort tidy of admin input into a legal id: {@code "Burned Flesh!"} -> {@code burned_flesh}. */
    public static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        StringBuilder out = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            }
        }
        // Collapse the runs of underscores that punctuation stripping tends to leave behind.
        String collapsed = out.toString().replaceAll("_{2,}", "_");
        while (collapsed.startsWith("_") || collapsed.startsWith("-")) {
            collapsed = collapsed.substring(1);
        }
        while (collapsed.endsWith("_") || collapsed.endsWith("-")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed.length() > 64 ? collapsed.substring(0, 64) : collapsed;
    }
}
