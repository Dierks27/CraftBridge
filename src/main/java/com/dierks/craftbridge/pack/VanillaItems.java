package com.dierks.craftbridge.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minecraft's own item definitions ({@code assets/minecraft/items/*.json}) for one version, as
 * bundled in the jar under {@code resourcepack/vanilla/items-<version>.json}. No Bukkit here.
 *
 * <p>Giving a custom item its own look means replacing the definition of its base item, and
 * that file then draws <em>every</em> item of that type. Everything that is not a textured
 * custom item must fall through to exactly what Minecraft would have drawn, so the fallback is
 * never written by hand: {@code tools/extract_vanilla_items.py} copies the definitions verbatim
 * out of Mojang's client jars, and CI checks the bundled files against those jars.
 *
 * <p>Each entry is {@code {"definition": <the file, verbatim>}}, plus {@code "parent"} for an
 * item drawn by a plain {@code minecraft:item/...} model built on a layer-0 template
 * ({@code item/generated}, {@code item/handheld}, ...): the template its generated
 * replacement uses, so a custom sword is still held like a sword.
 */
public final class VanillaItems {

    /** Where the tables sit in the jar. */
    public static final String JAR_FOLDER = PackFiles.JAR_ROOT + "vanilla/";

    /** The template a generated item model uses when the base has none of its own. */
    public static final String GENERATED = "minecraft:item/generated";

    /** How a base item is drawn in vanilla, which decides the model its custom items get. */
    public enum Kind {
        /** A plain {@code minecraft:block/...} model: a block item, drawn as a cube. */
        BLOCK,
        /** A plain {@code minecraft:item/...} model: a flat item. */
        ITEM,
        /** Anything else: selects, tints, special renderers, composites. */
        OTHER
    }

    private final String version;
    private final Map<String, JsonObject> entries;

    private VanillaItems(String version, Map<String, JsonObject> entries) {
        this.version = version;
        this.entries = entries;
    }

    /** Parse one bundled table. */
    public static VanillaItems parse(byte[] json) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        String version = root.get("minecraft_version").getAsString();
        Map<String, JsonObject> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> item : root.getAsJsonObject("items").entrySet()) {
            JsonObject entry = item.getValue().getAsJsonObject();
            if (entry.has("definition") && entry.get("definition").isJsonObject()) {
                entries.put(item.getKey(), entry);
            }
        }
        return new VanillaItems(version, Collections.unmodifiableMap(entries));
    }

    /** {@code items-26.3.json} -> {@code 26.3}; null for any other name. */
    public static String versionOf(String fileName) {
        if (!fileName.startsWith("items-") || !fileName.endsWith(".json")) {
            return null;
        }
        String version = fileName.substring("items-".length(), fileName.length() - ".json".length());
        return version.isEmpty() ? null : version;
    }

    /**
     * The table to use on a server running {@code minecraftVersion}: the exact version, else the
     * one for the same major.minor ({@code 26.3.1} uses {@code 26.3}), else null. A version this
     * CraftBridge has no table for gets no custom item art rather than a guessed fallback.
     */
    public static String pick(String minecraftVersion, Collection<String> available) {
        if (minecraftVersion == null) {
            return null;
        }
        if (available.contains(minecraftVersion)) {
            return minecraftVersion;
        }
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length > 2) {
            String family = parts[0] + "." + parts[1];
            if (available.contains(family)) {
                return family;
            }
        }
        return null;
    }

    /**
     * The items whose vanilla look differs between the given tables (26.2 and 26.3 disagree on
     * {@code filled_map} and {@code light}). One pack serves every client version pack.mcmeta
     * allows, so for these there is no single vanilla definition to fall back to. An item only
     * some tables have is not listed: a client that does not know it never draws it.
     */
    public static Set<String> differing(Collection<VanillaItems> tables) {
        Set<String> out = new TreeSet<>();
        Map<String, JsonObject> first = new HashMap<>();
        for (VanillaItems table : tables) {
            for (Map.Entry<String, JsonObject> entry : table.entries.entrySet()) {
                JsonObject seen = first.putIfAbsent(entry.getKey(), entry.getValue());
                if (seen != null && !seen.equals(entry.getValue())) {
                    out.add(entry.getKey());
                }
            }
        }
        return out;
    }

    /** The Minecraft version this table was taken from. */
    public String version() {
        return version;
    }

    public int size() {
        return entries.size();
    }

    public boolean has(String item) {
        return entries.containsKey(item);
    }

    /** The item's vanilla definition (a copy, safe to change), or null when this version has no such item. */
    public JsonObject definition(String item) {
        JsonObject entry = entries.get(item);
        return entry == null ? null : entry.getAsJsonObject("definition").deepCopy();
    }

    /** How the item is drawn in vanilla, or null when this version has no such item. */
    public Kind kind(String item) {
        JsonObject entry = entries.get(item);
        return entry == null ? null : kindOf(entry.getAsJsonObject("definition"));
    }

    /**
     * The parent a generated flat model for a custom item on this base uses: the vanilla model's
     * own layer-0 template ({@code minecraft:item/handheld} for a sword), else
     * {@link #GENERATED}.
     */
    public String itemParent(String item) {
        JsonObject entry = entries.get(item);
        if (entry != null && entry.has("parent") && entry.get("parent").isJsonPrimitive()) {
            String parent = entry.get("parent").getAsString();
            if (parent.startsWith("minecraft:item/")) {
                return parent;
            }
        }
        return GENERATED;
    }

    /** A definition's kind: only a bare {@code {"type": "minecraft:model", "model": ...}} is a plain block or item. */
    static Kind kindOf(JsonObject definition) {
        JsonElement model = definition.get("model");
        if (model == null || !model.isJsonObject()) {
            return Kind.OTHER;
        }
        JsonObject m = model.getAsJsonObject();
        if (m.size() != 2 || !m.has("type") || !m.has("model") || !m.get("model").isJsonPrimitive()
                || !"minecraft:model".equals(m.get("type").getAsString())) {
            return Kind.OTHER;
        }
        String ref = m.get("model").getAsString();
        if (ref.startsWith("minecraft:block/") || ref.startsWith("block/")) {
            return Kind.BLOCK;
        }
        if (ref.startsWith("minecraft:item/") || ref.startsWith("item/")) {
            return Kind.ITEM;
        }
        return Kind.OTHER;
    }
}
