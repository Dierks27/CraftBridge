package com.dierks.craftbridge.items;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code plugins/CraftBridge/custom-items.yml}: one entry per custom item, alongside
 * {@code recipes.yml} and in the same shape as it (a single root map keyed by id, whole-file
 * rewrite on save, bad entries logged and skipped rather than aborting the load).
 *
 * <pre>
 * items:
 *   burned_zombie_flesh:
 *     material: DRIED_KELP
 *     name: '&lt;dark_gray&gt;Burned Zombie Flesh'
 *     lore:
 *       - '&lt;dark_gray&gt;&lt;italic&gt;Charred past recognition.'
 *   founders_skull:
 *     material: PLAYER_HEAD
 *     name: '&lt;gold&gt;Founder''s Skull'
 *     head-texture: 'eyJ0ZXh0dXJlcyI6...'
 * </pre>
 *
 * Names and lore are MiniMessage, matching every other admin-facing string in the plugin.
 */
public final class CustomItemStore {

    private final CraftBridgePlugin plugin;
    private final File file;
    private final Map<String, CustomItemDef> items = new LinkedHashMap<>();

    public CustomItemStore(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "custom-items.yml");
    }

    public Map<String, CustomItemDef> all() {
        return items;
    }

    public void put(CustomItemDef def) {
        items.put(def.id(), def);
        save();
    }

    public CustomItemDef remove(String id) {
        CustomItemDef removed = items.remove(id);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public void load() {
        items.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        items.putAll(parse(yaml));
    }

    /** Parse the entries of a custom-items YAML. Bad entries are logged and skipped. */
    public Map<String, CustomItemDef> parse(YamlConfiguration yaml) {
        Map<String, CustomItemDef> out = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) {
            return out;
        }
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(rawId);
            if (s == null) {
                continue;
            }
            String id = rawId.toLowerCase(Locale.ROOT);
            try {
                out.put(id, parseOne(id, s));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("custom-items.yml: item '" + rawId + "' skipped: " + ex.getMessage());
            }
        }
        return out;
    }

    private CustomItemDef parseOne(String id, ConfigurationSection s) {
        String materialName = s.getString("material");
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("unknown material '" + materialName + "'");
        }
        String name = s.getString("name", id);
        List<String> lore = s.getStringList("lore");
        String texture = s.getString("head-texture");
        return new CustomItemDef(id, material, name, lore, texture);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "CraftBridge custom items. Edited by /recipe items in-game; hand edits are fine too.",
                "Names and lore are MiniMessage. The id (the key) is what recipes reference and",
                "what is stamped into the item as craftbridge:cb_item - renaming a key orphans",
                "any recipe that used it, so add a new entry instead."));
        for (CustomItemDef def : items.values()) {
            String base = "items." + def.id();
            yaml.set(base + ".material", def.base().name());
            yaml.set(base + ".name", def.name());
            if (!def.lore().isEmpty()) {
                yaml.set(base + ".lore", new ArrayList<>(def.lore()));
            }
            if (def.headTexture() != null) {
                yaml.set(base + ".head-texture", def.headTexture());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save custom-items.yml: " + ex.getMessage());
        }
    }
}
