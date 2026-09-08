package com.dierks.craftbridge.config;

import com.dierks.craftbridge.sort.SortCategoryRules;
import com.dierks.craftbridge.sort.SortTrigger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed, validated view of {@code config.yml}. Built once per (re)load; nothing here
 * changes at runtime, so features can cache the instance they were given.
 */
public final class CraftBridgeConfig {

    private final JavaPlugin plugin;
    private final FileConfiguration raw;

    public CraftBridgeConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.raw = plugin.getConfig();
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
}
