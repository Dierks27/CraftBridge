package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.KeptEntries;
import com.dierks.craftbridge.util.SafeYaml;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** {@code plugins/CraftBridge/linked-workbenches.yml}: the list of placed workbenches. */
public final class WorkbenchStore {

    private final CraftBridgePlugin plugin;
    private final File file;
    private final Map<String, WorkbenchRecord> records = new LinkedHashMap<>();
    /**
     * List entries that did not parse, written back verbatim by {@link #save()} so a bad line
     * (or a block type from a newer plugin version) is not erased by the next placement.
     */
    private final List<Object> unreadable = new ArrayList<>();
    /**
     * What we could still read from those entries — their table key and display UUID — so the
     * orphan sweep leaves their display alone ({@link #isHeld}). Deleting it would make the
     * entry's eventual repair show a bare crafting table.
     */
    private final Set<String> heldKeys = new HashSet<>();
    private final Set<UUID> heldDisplays = new HashSet<>();
    /** The file exists but is not valid YAML: never save over it, and treat every display as held. */
    private boolean loadFailed;

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

    /**
     * {@link BlockKeys} of every placed block of {@code kind}. Used to keep terminals out of
     * storage scans — a Combo Chest's own barrel is a terminal, never storage.
     */
    public java.util.Set<String> keysOf(BlockKind kind) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (WorkbenchRecord r : records.values()) {
            if (r.kind() == kind) {
                out.add(BlockKeys.of(r.world(), r.x(), r.y(), r.z()));
            }
        }
        return out;
    }

    public void put(WorkbenchRecord record) {
        records.put(record.key(), record);
        forgetUnreadableAt(record.key());
        save();
    }

    public WorkbenchRecord remove(String key) {
        WorkbenchRecord removed = records.remove(key);
        if (removed != null) {
            forgetUnreadableAt(key);
            save();
        }
        return removed;
    }

    /**
     * Should the orphan sweep leave this display alone even though no loaded record claims it?
     * True when an entry that failed to load names its table key or its UUID, and for every
     * display when the whole file failed to load.
     */
    public boolean isHeld(String key, UUID display) {
        return loadFailed || (key != null && heldKeys.contains(key)) || heldDisplays.contains(display);
    }

    /** A block was placed or removed at {@code key} on purpose: an unreadable entry for it is obsolete. */
    private void forgetUnreadableAt(String key) {
        if (!heldKeys.contains(key)) {
            return;
        }
        unreadable.removeIf(o -> o instanceof Map<?, ?> m && key.equals(keyOf(m)));
        reindexHeld();
    }

    /** The table key an entry names, or null when its world/x/y/z are not all readable. */
    private static String keyOf(Map<?, ?> m) {
        if (m.get("world") == null || !(m.get("x") instanceof Number x)
                || !(m.get("y") instanceof Number y) || !(m.get("z") instanceof Number z)) {
            return null;
        }
        return WorkbenchRecord.keyOf(m.get("world").toString(), x.intValue(), y.intValue(), z.intValue());
    }

    /** Keep an entry that did not parse, and remember which table and display it names. */
    private void keepUnreadable(Object entry) {
        unreadable.add(KeptEntries.copy(entry));
        reindexHeld();
    }

    /** Rebuild {@link #heldKeys}/{@link #heldDisplays} from the kept entries. */
    private void reindexHeld() {
        heldKeys.clear();
        heldDisplays.clear();
        for (Object entry : unreadable) {
            if (!(entry instanceof Map<?, ?> m)) {
                continue;
            }
            String key = keyOf(m);
            if (key != null) {
                heldKeys.add(key);
            }
            try {
                if (m.get("display") != null) {
                    heldDisplays.add(UUID.fromString(m.get("display").toString()));
                }
            } catch (IllegalArgumentException ignored) {
                // No readable display UUID: the table key above (if any) still protects it.
            }
        }
    }

    public void load() {
        records.clear();
        unreadable.clear();
        heldKeys.clear();
        heldDisplays.clear();
        loadFailed = false;
        YamlConfiguration yaml = SafeYaml.loadOrNull(file, plugin.getLogger());
        if (yaml == null) {
            loadFailed = true;
            return;
        }
        if (yaml.contains("workbenches") && !yaml.isList("workbenches")) {
            plugin.getLogger().severe("linked-workbenches.yml: 'workbenches' is not a list, so none were loaded and "
                    + "CraftBridge will NOT save over the file this session. Fix it and run /craftbridge reload.");
            loadFailed = true;
            return;
        }
        List<?> entries = yaml.getList("workbenches", List.of());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> m)) {
                plugin.getLogger().warning("linked-workbenches.yml: keeping unreadable entry " + entry + " (not a map)");
                keepUnreadable(entry);
                continue;
            }
            try {
                BlockKind kind = BlockKind.byId(m.get("type") == null ? "workbench" : m.get("type").toString());
                if (kind == null) {
                    plugin.getLogger().warning("linked-workbenches.yml: unknown type '" + m.get("type")
                            + "', keeping the entry as it is but not loading it: " + m);
                    keepUnreadable(m);
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
                plugin.getLogger().warning("linked-workbenches.yml: keeping bad entry as it is but not loading it: "
                        + m + " (" + ex.getMessage() + ")");
                keepUnreadable(m);
            }
        }
    }

    public void save() {
        if (loadFailed) {
            SafeYaml.refuseSave(file, plugin.getLogger());
            return;
        }
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
        List<Object> all = new ArrayList<>(list);
        all.addAll(unreadable);
        yaml.set("workbenches", all);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save linked-workbenches.yml: " + ex.getMessage());
        }
    }
}
