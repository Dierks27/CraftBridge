package com.dierks.craftbridge.config;

import com.dierks.craftbridge.sort.SortCategoryRules;
import com.dierks.craftbridge.sort.SortTrigger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed, validated view of {@code config.yml}. Built once per (re)load; nothing here
 * changes at runtime, so features can cache the instance they were given.
 */
public final class CraftBridgeConfig {

    private final JavaPlugin plugin;
    private final FileConfiguration raw;

    /** Top-level sections the plugin expects; a config from an older version may lack some. */
    private static final List<String> SECTIONS = List.of("features", "sorting", "linked-workbench",
            "combo-chest", "jei");

    public CraftBridgeConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.raw = plugin.getConfig();
        reportMissingSections();
    }

    /**
     * A config.yml carried over from an older version is missing whatever sections were added
     * since. Every accessor falls back to a built-in default, so nothing throws and nothing
     * stops working — but say so once at boot, because "the Combo Chest is not craftable"
     * should not be something an admin has to work out from a stack trace.
     */
    private void reportMissingSections() {
        List<String> missing = new ArrayList<>();
        for (String section : SECTIONS) {
            if (!raw.isConfigurationSection(section)) {
                missing.add(section);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().info("config.yml has no " + String.join(", ", missing)
                    + " section; using the built-in defaults for those. Copy the section from the"
                    + " jar's config.yml (or delete config.yml and restart) to customise it.");
        }
    }

    public FileConfiguration raw() {
        return raw;
    }

    public boolean debug() {
        return raw.getBoolean("debug", false);
    }

    // ---- feature switches ---------------------------------------------------

    public boolean sortingEnabled() {
        return raw.getBoolean("features.sorting", true);
    }

    public boolean recipesEnabled() {
        return raw.getBoolean("features.recipes", true);
    }

    public boolean linkedWorkbenchEnabled() {
        return raw.getBoolean("features.linked-workbench", true);
    }

    public boolean jeiTransferEnabled() {
        return raw.getBoolean("features.jei-transfer", true);
    }

    public boolean jeiRecipeSyncEnabled() {
        return raw.getBoolean("features.jei-recipe-sync", true);
    }

    /** Recipe types to sync to JEI, in priority order (later ones are dropped first if the payload is too big). */
    public List<String> jeiRecipeSyncTypes() {
        List<String> types = raw.getStringList("jei.recipe-sync.types");
        return types.isEmpty()
                ? List.of("minecraft:crafting", "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
                        "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing")
                : types;
    }

    // ---- sorting --------------------------------------------------------------

    public SortTrigger sortDefaultTrigger() {
        String name = raw.getString("sorting.default-trigger", "DOUBLE_CLICK_OUTSIDE");
        try {
            return SortTrigger.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("sorting.default-trigger '" + name + "' is not a trigger; using DOUBLE_CLICK_OUTSIDE.");
            return SortTrigger.DOUBLE_CLICK_OUTSIDE;
        }
    }

    public long sortDoubleClickMillis() {
        return Math.max(100, raw.getLong("sorting.double-click-ms", 400));
    }

    public boolean sortPlayerInventoryAllowed() {
        return raw.getBoolean("sorting.player-inventory.allowed", true);
    }

    public boolean sortPlayerInventoryDefault() {
        return raw.getBoolean("sorting.player-inventory.default", false);
    }

    public boolean sortFeedbackDefault() {
        return raw.getBoolean("sorting.feedback-default", true);
    }

    public boolean sortRespectProtection() {
        return raw.getBoolean("sorting.respect-protection", true);
    }

    /** The ordered category list; falls back to the built-in defaults if the section is missing. */
    public SortCategoryRules sortCategories() {
        List<SortCategoryRules.Category> categories = new ArrayList<>();
        List<?> entries = raw.getList("sorting.categories");
        if (entries != null) {
            for (Object entry : entries) {
                if (entry instanceof java.util.Map<?, ?> map) {
                    Object name = map.get("name");
                    Object match = map.get("match");
                    List<String> patterns = new ArrayList<>();
                    if (match instanceof List<?> list) {
                        for (Object o : list) {
                            if (o != null) {
                                patterns.add(o.toString());
                            }
                        }
                    }
                    if (name != null && !patterns.isEmpty()) {
                        categories.add(new SortCategoryRules.Category(name.toString(), patterns));
                    }
                } else if (entry instanceof ConfigurationSection section) {
                    categories.add(new SortCategoryRules.Category(
                            section.getString("name", "?"), section.getStringList("match")));
                }
            }
        }
        if (categories.isEmpty()) {
            plugin.getLogger().warning("sorting.categories is empty or malformed; using built-in category order.");
            return SortCategoryRules.defaults();
        }
        return new SortCategoryRules(categories);
    }

    // ---- linked workbench -----------------------------------------------------

    /** How the head display is rendered over the table. All live-tunable via /craftbridge workbench display. */
    public record WorkbenchDisplay(ItemDisplay.ItemDisplayTransform transform, float scale,
                                   double offsetX, double offsetY, double offsetZ, float yawOffset) {
        @Override
        public String toString() {
            return "transform=" + transform + " scale=" + scale + " offset=(" + offsetX + ", " + offsetY + ", " + offsetZ
                    + ") yaw-offset=" + yawOffset;
        }
    }

    public int workbenchRadius() {
        return radius(com.dierks.craftbridge.workbench.BlockKind.WORKBENCH);
    }

    public int radius(com.dierks.craftbridge.workbench.BlockKind kind) {
        return Math.max(1, Math.min(32, raw.getInt(kind.configSection() + ".radius", 8)));
    }

    /**
     * Base64 {@code textures} value for a kind's head, or "" for none. An unset or blank
     * value means "the built-in look for this block"; {@code none} turns the head off and
     * shows {@code display-item} instead.
     */
    public String headTexture(com.dierks.craftbridge.workbench.BlockKind kind) {
        String configured = raw.getString(kind.configSection() + ".head-texture", "");
        if (configured == null || configured.isBlank()) {
            return kind.defaultHeadTexture();
        }
        return configured.trim().equalsIgnoreCase("none") ? "" : configured.trim();
    }

    /** Item shown by the display (and used as the place-item) when a kind has no head texture. */
    public Material displayMaterial(com.dierks.craftbridge.workbench.BlockKind kind) {
        String name = raw.getString(kind.configSection() + ".display-item",
                kind == com.dierks.craftbridge.workbench.BlockKind.COMBO_CHEST ? "CHEST" : "PLAYER_HEAD");
        Material m = Material.matchMaterial(name == null ? "" : name);
        return m != null && m.isItem() ? m : Material.CHEST;
    }

    public WorkbenchDisplay displayFor(com.dierks.craftbridge.workbench.BlockKind kind) {
        String base = kind.configSection() + ".display.";
        // Both kinds default to a head model now, so both use the head geometry; a kind
        // switched to display-item ("head-texture: none") wants scale ~1.16 at y 0.5.
        ItemDisplay.ItemDisplayTransform transform;
        String t = raw.getString(base + "transform", "NONE");
        try {
            transform = ItemDisplay.ItemDisplayTransform.valueOf(t.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning(base + "transform '" + t + "' is not valid; using NONE.");
            transform = ItemDisplay.ItemDisplayTransform.NONE;
        }
        return new WorkbenchDisplay(transform,
                (float) raw.getDouble(base + "scale", 2.02),
                raw.getDouble(base + "offset-x", 0.5),
                raw.getDouble(base + "offset-y", 1.005),
                raw.getDouble(base + "offset-z", 0.5),
                (float) raw.getDouble(base + "yaw-offset", 0));
    }

    public boolean workbenchRespectProtection() {
        return raw.getBoolean("linked-workbench.respect-protection", true);
    }

    /** Show nearby storage in the player's empty inventory slots (packet-only) while a linked table is open. */
    public boolean workbenchPhantomSlots() {
        return raw.getBoolean("linked-workbench.phantom-slots", true);
    }

    /** Empty slots left free of phantoms so JEI can still shuffle items out of the grid. */
    public int workbenchPhantomReserve() {
        return Math.max(0, Math.min(9, raw.getInt("linked-workbench.phantom-reserve-empty-slots", 2)));
    }

    public WorkbenchDisplay workbenchDisplay() {
        return displayFor(com.dierks.craftbridge.workbench.BlockKind.WORKBENCH);
    }

    public boolean recipeEnabled(com.dierks.craftbridge.workbench.BlockKind kind) {
        return raw.getBoolean(kind.configSection() + ".recipe.enabled", true);
    }

    public List<String> recipeShape(com.dierks.craftbridge.workbench.BlockKind kind) {
        List<String> shape = raw.getStringList(kind.configSection() + ".recipe.shape");
        return shape.isEmpty() ? kind.defaultRecipeShape() : shape;
    }

    public Map<Character, Material> recipeIngredients(com.dierks.craftbridge.workbench.BlockKind kind) {
        Map<Character, Material> out = new LinkedHashMap<>();
        ConfigurationSection section = raw.getConfigurationSection(kind.configSection() + ".recipe.ingredients");
        if (section == null) {
            return kind.defaultRecipeIngredients();
        }
        for (String key : section.getKeys(false)) {
            String name = section.getString(key, "");
            Material material = Material.matchMaterial(name);
            if (key.length() != 1 || material == null) {
                plugin.getLogger().warning(kind.configSection() + ".recipe.ingredients." + key + " = '" + name + "' is not valid; ignored.");
                continue;
            }
            out.put(key.charAt(0), material);
        }
        return out;
    }
}
