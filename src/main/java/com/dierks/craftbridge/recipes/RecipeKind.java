package com.dierks.craftbridge.recipes;

import java.util.Locale;

/**
 * What kind of recipe an entry in {@code recipes.yml} is. Deliberately Bukkit-free so the
 * defaults and the yaml token round-trip can be unit tested.
 *
 * <p>The two crafting kinds use the 3x3 grid; the four cooking kinds take a single input
 * and add a cook time and an experience reward. Each cooking kind is its own recipe — a
 * "works in any cooker" entry is intentionally <em>not</em> offered, because that is not
 * how vanilla datapacks model it and it would hide which of the four the admin meant.
 */
public enum RecipeKind {

    SHAPED("shaped", 0, false),
    SHAPELESS("shapeless", 0, false),
    /** Ordinary furnace: 200 ticks (10s). */
    FURNACE("furnace", 200, true),
    /** Smoker: food, twice as fast as a furnace. */
    SMOKER("smoker", 100, true),
    /** Blast furnace: ores, twice as fast as a furnace. */
    BLAST_FURNACE("blast_furnace", 100, true),
    /** Campfire: 600 ticks (30s). */
    CAMPFIRE("campfire", 600, true);

    /** Default experience for a new cooking recipe when the admin does not set one. */
    public static final float DEFAULT_EXPERIENCE = 0.1f;

    private final String token;
    private final int defaultCookingTime;
    private final boolean cooking;

    RecipeKind(String token, int defaultCookingTime, boolean cooking) {
        this.token = token;
        this.defaultCookingTime = defaultCookingTime;
        this.cooking = cooking;
    }

    /** The value written to {@code type:} in recipes.yml. */
    public String token() {
        return token;
    }

    /** Cook time in ticks a fresh recipe of this kind starts with. 0 for crafting kinds. */
    public int defaultCookingTime() {
        return defaultCookingTime;
    }

    public boolean isCooking() {
        return cooking;
    }

    public boolean isCrafting() {
        return !cooking;
    }

    /** How many input slots the editor shows: 9 for crafting, 1 for cooking. */
    public int inputSlots() {
        return cooking ? 1 : 9;
    }

    /** Human label for GUIs and lore. */
    public String label() {
        return switch (this) {
            case SHAPED -> "Shaped";
            case SHAPELESS -> "Shapeless";
            case FURNACE -> "Furnace";
            case SMOKER -> "Smoker";
            case BLAST_FURNACE -> "Blast furnace";
            case CAMPFIRE -> "Campfire";
        };
    }

    /**
     * Parse a {@code type:} token. Unknown values fall back to {@link #SHAPED}, matching the
     * old behaviour where anything that was not {@code shapeless} meant shaped — a recipes.yml
     * written by an older build keeps working untouched.
     */
    public static RecipeKind fromToken(String raw) {
        if (raw == null) {
            return SHAPED;
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (RecipeKind kind : values()) {
            if (kind.token.equals(needle)) {
                return kind;
            }
        }
        // Tolerate the spellings a hand-editing admin is most likely to reach for.
        return switch (needle) {
            case "smelting", "smelt" -> FURNACE;
            case "blasting", "blast" -> BLAST_FURNACE;
            case "smoking" -> SMOKER;
            case "campfire_cooking" -> CAMPFIRE;
            default -> SHAPED;
        };
    }

    /** The next kind in the editor's type selector. */
    public RecipeKind next() {
        RecipeKind[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Clamp a cook time to something the server will accept. Vanilla stores it as an int and
     * a non-positive value makes the block never finish, so the floor is one tick; the ceiling
     * is an hour, which is far past anything useful but keeps a typo from freezing a furnace.
     */
    public static int clampCookingTime(int ticks) {
        return Math.max(1, Math.min(72_000, ticks));
    }

    /** Clamp experience to the range vanilla recipes use. */
    public static float clampExperience(float xp) {
        if (Float.isNaN(xp) || xp < 0f) {
            return 0f;
        }
        return Math.min(100f, xp);
    }
}
