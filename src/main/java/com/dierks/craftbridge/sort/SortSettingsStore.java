package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.CraftBridgePlugin;
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
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(id);
            if (s == null) {
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
                        s.getBoolean("feedback", defaults.feedback())));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("sort-players.yml: ignoring bad UUID '" + id + "'");
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerSortSettings> e : settings.entrySet()) {
            String base = "players." + e.getKey();
            yaml.set(base + ".trigger", e.getValue().trigger().name());
            yaml.set(base + ".sort-player-inventory", e.getValue().sortPlayerInventory());
            yaml.set(base + ".feedback", e.getValue().feedback());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save sort-players.yml: " + ex.getMessage());
        }
    }
}
