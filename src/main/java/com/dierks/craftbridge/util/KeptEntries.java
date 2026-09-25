package com.dierks.craftbridge.util;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Entries of a keyed data file ({@code recipes:}, {@code items:}, {@code players:}) that did
 * not parse, held verbatim so the next whole-file save writes them back instead of erasing them.
 *
 * <p>An entry can stop parsing for reasons that are not the admin's fault and fix themselves:
 * a recipe whose result is a custom item that was deleted, a Material renamed between server
 * versions, a hand edit with one typo. Dropping it from memory is fine — it cannot be registered
 * anyway — but dropping it from the FILE on the next unrelated save turned a warning in the log
 * into permanent data loss. The kept copy is forgotten only when an entry with the same id is
 * saved or deleted through the plugin, which is the admin replacing it on purpose.
 *
 * <p>Ids are compared ignoring case, because the stores lower-case ids on load while the kept
 * copy keeps the key exactly as it was written.
 */
public final class KeptEntries {

    private final Map<String, Object> raw = new LinkedHashMap<>();

    public void clear() {
        raw.clear();
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    public int size() {
        return raw.size();
    }

    /** The kept keys, exactly as they were written in the file. */
    public Set<String> keys() {
        return raw.keySet();
    }

    /** The kept value (a deep copy of a section is a plain {@link Map}), or null. */
    public Object get(String key) {
        return raw.get(key);
    }

    /** Hold one unparsed entry. A section is deep-copied into plain maps so it outlives its file. */
    public void keep(String key, Object value) {
        raw.put(key, copy(value));
    }

    /** The admin saved or deleted {@code id} on purpose: the kept copy under that id is obsolete. */
    public void forget(String id) {
        raw.keySet().removeIf(k -> sameId(k, id));
    }

    /**
     * Write every kept entry under {@code root}, skipping any whose id a live entry already
     * uses (the live one was just written and wins).
     */
    public void writeInto(ConfigurationSection root, Set<String> liveIds) {
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (liveIds.stream().anyMatch(id -> sameId(e.getKey(), id))) {
                continue;
            }
            if (e.getValue() instanceof Map<?, ?> map) {
                root.createSection(e.getKey(), map);
            } else {
                root.set(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean sameId(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    /** Deep copy: sections become maps, lists and maps are copied, leaves are shared (immutable). */
    public static Object copy(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, copy(section.get(key)));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(copy(o));
            }
            return out;
        }
        return value;
    }
}
