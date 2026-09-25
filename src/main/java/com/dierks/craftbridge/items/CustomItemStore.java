package com.dierks.craftbridge.items;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.KeptEntries;
import com.dierks.craftbridge.util.SafeYaml;
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
import java.util.function.BiConsumer;

/**
 * {@code plugins/CraftBridge/custom-items.yml}: one entry per custom item, alongside
 * {@code recipes.yml} and in the same shape as it (a single root map keyed by id, whole-file
 * rewrite on save, bad entries logged and skipped rather than aborting the load — and kept
 * verbatim in the file, never erased by the next save).
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
    /** Entries that did not parse (a renamed Material, a typo): written back verbatim on save. */
    private final KeptEntries unreadable = new KeptEntries();
    /** custom-items.yml exists but is not valid YAML: never save over it this session. */
    private boolean loadFailed;

    public CustomItemStore(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "custom-items.yml");
    }

    public Map<String, CustomItemDef> all() {
        return items;
    }

    public void put(CustomItemDef def) {
        items.put(def.id(), def);
        unreadable.forget(def.id());
        save();
    }

    public CustomItemDef remove(String id) {
        CustomItemDef removed = items.remove(id);
        if (removed != null) {
            unreadable.forget(id);
            save();
        }
        return removed;
    }

    /**
     * Is {@code id} the key of an entry that is in the file but did not load? A new item must not
     * take it: saving would silently replace the admin's broken-but-fixable definition.
     */
    public boolean isUnreadable(String id) {
        return unreadable.keys().stream().anyMatch(k -> k.equalsIgnoreCase(id));
    }

    public void load() {
        items.clear();
        unreadable.clear();
        loadFailed = false;
        YamlConfiguration yaml = SafeYaml.loadOrNull(file, plugin.getLogger());
        if (yaml == null) {
            loadFailed = true;
            return;
        }
        if (yaml.contains("items") && !yaml.isConfigurationSection("items")) {
            plugin.getLogger().severe("custom-items.yml: 'items' is not a map of items, so none were loaded and "
                    + "CraftBridge will NOT save over the file this session. Fix it and run /craftbridge reload.");
            loadFailed = true;
            return;
        }
        items.putAll(parse(yaml, unreadable::keep));
        if (!unreadable.isEmpty()) {
            plugin.getLogger().warning("custom-items.yml: " + unreadable.size() + " item(s) could not be loaded "
                    + "(see above). They stay in the file untouched until fixed or replaced.");
        }
    }

    /** Parse the entries of a custom-items YAML. Bad entries are logged and skipped. */
    public Map<String, CustomItemDef> parse(YamlConfiguration yaml) {
        return parse(yaml, (id, raw) -> { });
    }

    /** As {@link #parse(YamlConfiguration)}, also handing each unparsed entry (key as written, raw value) to {@code unparsed}. */
    private Map<String, CustomItemDef> parse(YamlConfiguration yaml, BiConsumer<String, Object> unparsed) {
        Map<String, CustomItemDef> out = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("items");
        if (root == null) {
            return out;
        }
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(rawId);
            if (s == null) {
                plugin.getLogger().warning("custom-items.yml: item '" + rawId + "' skipped: not a section");
                unparsed.accept(rawId, root.get(rawId));
                continue;
            }
            String id = rawId.toLowerCase(Locale.ROOT);
            try {
                out.put(id, parseOne(id, s));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("custom-items.yml: item '" + rawId + "' skipped: " + ex.getMessage());
                unparsed.accept(rawId, s);
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
        if (loadFailed) {
            SafeYaml.refuseSave(file, plugin.getLogger());
            return;
        }
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
        if (!unreadable.isEmpty()) {
            ConfigurationSection root = yaml.getConfigurationSection("items");
            unreadable.writeInto(root == null ? yaml.createSection("items") : root, items.keySet());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save custom-items.yml: " + ex.getMessage());
        }
    }
}
