package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code plugins/CraftBridge/linked-workbenches.yml}: the list of placed workbenches. */
public final class WorkbenchStore {

    private final CraftBridgePlugin plugin;
    private final File file;
    private final Map<String, WorkbenchRecord> records = new LinkedHashMap<>();

    public WorkbenchStore(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "linked-workbenches.yml");
    }

    public Collection<WorkbenchRecord> all() {
        return records.values();
    }

    public WorkbenchRecord at(Block block) {
        return records.get(WorkbenchRecord.keyOf(block));
    }

    public WorkbenchRecord byKey(String key) {
        return records.get(key);
    }

    public boolean isLinked(Block block) {
        return records.containsKey(WorkbenchRecord.keyOf(block));
    }

    /** Block locations of every placed block of {@code kind} (used to keep terminals out of storage scans). */
    public java.util.Set<org.bukkit.Location> locationsOf(BlockKind kind) {
        java.util.Set<org.bukkit.Location> out = new java.util.HashSet<>();
        for (WorkbenchRecord r : records.values()) {
            if (r.kind() == kind) {
                org.bukkit.Location loc = r.location();
                if (loc != null) {
                    out.add(loc);
                }
            }
        }
        return out;
    }

    public void put(WorkbenchRecord record) {
        records.put(record.key(), record);
        save();
    }

    public WorkbenchRecord remove(String key) {
        WorkbenchRecord removed = records.remove(key);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public void load() {
        records.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("workbenches")) {
            try {
                BlockKind kind = BlockKind.byId(m.get("type") == null ? "workbench" : m.get("type").toString());
                if (kind == null) {
                    plugin.getLogger().warning("linked-workbenches.yml: unknown type '" + m.get("type") + "', skipping " + m);
                    continue;
                }
                WorkbenchRecord r = new WorkbenchRecord(kind,
                        String.valueOf(m.get("world")),
                        ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(),
                        ((Number) m.get("z")).intValue(),
                        m.get("display") == null ? null : UUID.fromString(m.get("display").toString()),
                        m.get("owner") == null ? null : UUID.fromString(m.get("owner").toString()),
                        m.get("yaw") == null ? 0f : ((Number) m.get("yaw")).floatValue());
                records.put(r.key(), r);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("linked-workbenches.yml: skipping bad entry " + m + " (" + ex.getMessage() + ")");
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (WorkbenchRecord r : records.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.kind().id());
            m.put("world", r.world());
            m.put("x", r.x());
            m.put("y", r.y());
            m.put("z", r.z());
            m.put("display", r.display() == null ? null : r.display().toString());
            m.put("owner", r.owner() == null ? null : r.owner().toString());
            m.put("yaw", (double) r.yaw());
            list.add(m);
        }
        yaml.set("workbenches", list);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save linked-workbenches.yml: " + ex.getMessage());
        }
    }
}
