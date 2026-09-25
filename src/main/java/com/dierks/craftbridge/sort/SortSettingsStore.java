package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.KeptEntries;
import com.dierks.craftbridge.util.SafeYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-UUID settings in {@code plugins/CraftBridge/sort-players.yml}. Small enough to
 * keep fully in memory and rewrite on every change.
 */
public final class SortSettingsStore {

    private final CraftBridgePlugin plugin;
    private final File file;
    private final PlayerSortSettings defaults;
    private final Map<UUID, PlayerSortSettings> settings = new HashMap<>();
    /** Entries that did not parse (a bad UUID key): written back verbatim on save. */
    private final KeptEntries unreadable = new KeptEntries();
    /** The file exists but is not valid YAML: never save over it this session. */
    private boolean loadFailed;

    public SortSettingsStore(CraftBridgePlugin plugin, PlayerSortSettings defaults) {
        this.plugin = plugin;
        this.defaults = defaults;
        this.file = new File(plugin.getDataFolder(), "sort-players.yml");
        load();
    }

    public PlayerSortSettings defaults() {
        return defaults;
    }

    public PlayerSortSettings get(UUID player) {
        return settings.getOrDefault(player, defaults);
    }

    public void put(UUID player, PlayerSortSettings value) {
        settings.put(player, value);
        save();
    }

    private void load() {
        settings.clear();
        unreadable.clear();
        loadFailed = false;
        YamlConfiguration yaml = SafeYaml.loadOrNull(file, plugin.getLogger());
        if (yaml == null) {
            loadFailed = true;
            return;
        }
        if (yaml.contains("players") && !yaml.isConfigurationSection("players")) {
            plugin.getLogger().severe("sort-players.yml: 'players' is not a map, so no settings were loaded and "
                    + "CraftBridge will NOT save over the file this session.");
            loadFailed = true;
            return;
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(id);
            if (s == null) {
                plugin.getLogger().warning("sort-players.yml: keeping unreadable entry '" + id + "' (not a section)");
                unreadable.keep(id, players.get(id));
                continue;
            }
            try {
                UUID uuid = UUID.fromString(id);
                SortTrigger trigger = defaults.trigger();
                String t = s.getString("trigger");
                if (t != null) {
                    try {
                        trigger = SortTrigger.valueOf(t.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown trigger (renamed?) — fall back to the default.
                    }
                }
                settings.put(uuid, new PlayerSortSettings(trigger,
                        s.getBoolean("sort-player-inventory", defaults.sortPlayerInventory()),
                        s.getBoolean("feedback", defaults.feedback()),
                        s.getBoolean("middle-click", defaults.middleClick())));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("sort-players.yml: ignoring bad UUID '" + id + "' (kept in the file)");
                unreadable.keep(id, s);
            }
        }
    }

    private void save() {
        if (loadFailed) {
            SafeYaml.refuseSave(file, plugin.getLogger());
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerSortSettings> e : settings.entrySet()) {
            String base = "players." + e.getKey();
            yaml.set(base + ".trigger", e.getValue().trigger().name());
            yaml.set(base + ".sort-player-inventory", e.getValue().sortPlayerInventory());
            yaml.set(base + ".feedback", e.getValue().feedback());
            yaml.set(base + ".middle-click", e.getValue().middleClick());
        }
        if (!unreadable.isEmpty()) {
            ConfigurationSection root = yaml.getConfigurationSection("players");
            java.util.Set<String> live = new java.util.HashSet<>();
            settings.keySet().forEach(u -> live.add(u.toString()));
            unreadable.writeInto(root == null ? yaml.createSection("players") : root, live);
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save sort-players.yml: " + ex.getMessage());
        }
    }
}
