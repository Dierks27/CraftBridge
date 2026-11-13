package com.dierks.craftbridge.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Puts the server's one resource pack together from its sources. No Bukkit here, so every rule is
 * tested on plain maps.
 *
 * <p>A source is a set of files laid out like a resource pack ({@code assets/<namespace>/...},
 * plus an optional {@code glyphs.json} at its root). Sources are ranked by {@code resource-pack.priority}
 * (then by id), never by the order plugins loaded in, and:
 * <ul>
 *   <li>fonts ({@code assets/*}{@code /font/**.json}) merge their {@code providers}, atlases
 *       ({@code atlases/**.json}) their {@code sources}, and {@code sounds.json} and
 *       {@code lang/*.json} their keys (a key in two sources: the higher one's, logged);</li>
 *   <li>Minecraft's item definitions ({@code assets/minecraft/items/*.json}) merge too: a
 *       {@code select} on {@code custom_model_data} adds its cases on top of another source's
 *       definition, which becomes the fallback ({@link #mergeItem});</li>
 *   <li>any other file in two sources is a conflict, logged as an error with both names; the
 *       higher-ranked source's copy is used. The same bytes in both is not a conflict;</li>
 *   <li>every file that replaces one of Minecraft's own (anything under {@code assets/minecraft/}
 *       but the merged kinds) is listed, so it is obvious what changes vanilla;</li>
 *   <li>a source's {@code pack.mcmeta} is dropped: this writes its own, for the server's version;</li>
 *   <li>a JSON file that does not parse is left out with a warning, and the rest of the pack
 *       is built; models whose textures or parents are missing, item definitions whose models
 *       are missing and fonts whose bitmaps are missing are warned about.</li>
 * </ul>
 * Glyphs declared in the sources' {@code glyphs.json} become {@code bitmap} providers in
 * {@code minecraft:default} (so plain strings can use them), next to a {@code space} provider
 * with the fixed space characters ({@link Glyphs#SPACES}).
 */
public final class PackAssembler {

    /** The font every glyph and space goes in: the one plain chat, titles and lore use. */
    public static final String GLYPH_FONT = "assets/minecraft/font/default.json";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern FONT = Pattern.compile("assets/[^/]+/font/.+\\.json");
    private static final Pattern ATLAS = Pattern.compile("assets/[^/]+/atlases/.+\\.json");
    private static final Pattern SOUNDS = Pattern.compile("assets/[^/]+/sounds\\.json");
    private static final Pattern LANG = Pattern.compile("assets/[^/]+/lang/[^/]+\\.json");
    private static final Pattern VANILLA_ITEM = Pattern.compile("assets/minecraft/items/.+\\.json");
    private static final Pattern MODEL = Pattern.compile("assets/[^/]+/models/.+\\.json");
    private static final Pattern ITEM_DEFINITION = Pattern.compile("assets/[^/]+/items/.+\\.json");

    private PackAssembler() {
    }

    /**
     * One source's files.
     *
     * @param origin where it comes from, for /craftbridge pack (e.g. {@code plugin HCM}, {@code folder pack/sources/x})
     */
    public record Source(String id, String origin, SortedMap<String, byte[]> files) {
    }

    /**
     * @param priority    source ids, highest first; sources not named come after, by id
     * @param formatMajor the resource pack format of the server's Minecraft version
     */
    public record Settings(List<String> priority, int formatMajor, int formatMinor, String description) {
    }

    /** A source as it went into the pack. */
    public record SourceInfo(String id, String origin, int files, int glyphs) {
    }

    /**
     * A glyph in the pack.
     *
     * @param source  the source that declared it, or null for a key only asked for through the API
     * @param drawn   its texture is in the pack, so it has a bitmap
     */
    public record GlyphInfo(String key, int codepoint, String source, String texture, int height, int ascent,
                            boolean drawn) {
    }

    /**
     * What a build produced.
     *
     * @param errors   conflicts: the same file in two sources
     * @param warnings files left out, references that lead nowhere, and anything else worth fixing
     * @param notes    what was done that is worth one line in the log
     */
    public record Result(SortedMap<String, byte[]> files, List<SourceInfo> sources, Map<String, List<String>> vanillaOverrides,
                         List<GlyphInfo> glyphs, List<String> errors, List<String> warnings, List<String> notes) {
    }

    /** The sources in the order they win in: {@code priority} first (as listed), then the rest by id. */
    public static List<Source> order(Collection<Source> sources, List<String> priority) {
        List<String> ranked = priority.stream().map(s -> s.trim().toLowerCase(java.util.Locale.ROOT)).toList();
        List<Source> out = new ArrayList<>(sources);
        out.sort(Comparator.<Source>comparingInt(s -> {
            int at = ranked.indexOf(s.id());
            return at < 0 ? Integer.MAX_VALUE : at;
        }).thenComparing(Source::id));
        return out;
    }

    /** A file from one source, parsed when it is JSON. */
    private record Part(String source, byte[] bytes, JsonElement json) {
    }

    private record GlyphFrom(String source, Glyphs.Declared glyph) {
    }

    /**
     * Build the pack's files.
     *
     * @param charFor the character of a glyph key (assigning one the first time), -1 when none is left
     */
    public static Result assemble(Collection<Source> sources, Settings settings, ToIntFunction<String> charFor) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, List<Part>> byPath = new TreeMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> glyphCounts = new HashMap<>();
        Map<String, Set<String>> namespaces = new TreeMap<>();
        Map<String, GlyphFrom> declared = new TreeMap<>();
        byte[] icon = null;

        List<Source> ordered = order(sources, settings.priority());
        for (Source source : ordered) {
            String id = source.id();
            SortedMap<String, byte[]> files = unwrap(source, notes);
            int count = 0;
            Set<String> leftOutFolders = new LinkedHashSet<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                String path = file.getKey();
                byte[] bytes = file.getValue();
                if (path.equals("pack.mcmeta")) {
                    checkMcmeta(id, bytes, warnings);
                    continue;
                }
                if (path.equals("pack.png")) {
                    if (icon == null) {
                        icon = bytes;
                    }
                    continue;
                }
                if (path.equals(Glyphs.FILE)) {
                    readGlyphs(id, bytes, declared, glyphCounts, errors, warnings);
                    continue;
                }
                if (!path.startsWith("assets/")) {
                    int slash = path.indexOf('/');
                    if (slash < 0) {
                        notes.add(id + ": " + path + " at the pack's root is not part of a resource pack; left out.");
                    } else {
                        leftOutFolders.add(path.substring(0, slash + 1));
                    }
                    continue;
                }
                String[] parts = path.split("/", 3);
                if (parts.length < 3 || parts[2].isEmpty()) {
                    warnings.add(id + ": " + path + " is not inside a namespace folder (assets/<namespace>/...); left out.");
                    continue;
                }
                if (!NAMESPACE.matcher(parts[1]).matches() || !RESOURCE_PATH.matcher(parts[2]).matches()) {
                    warnings.add(id + ": " + path + " has upper-case letters or characters Minecraft does not allow in a"
                            + " resource name, so the client would ignore it; left out. Rename it in lower case.");
                    continue;
                }
                JsonElement json = null;
                if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                    try {
                        json = parse(bytes);
                    } catch (IllegalArgumentException broken) {
                        warnings.add(id + ": " + path + " is not valid JSON (" + broken.getMessage() + "); left out.");
                        continue;
                    }
                    if (json == null) {
                        warnings.add(id + ": " + path + " is not strict JSON (comments, trailing commas or unquoted"
                                + " keys); Minecraft may refuse it. Kept, but fix it.");
                        json = lenient(bytes);
                    }
                }
                byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(new Part(id, bytes, json));
                if (!parts[1].equals("minecraft")) {
                    namespaces.computeIfAbsent(parts[1], k -> new LinkedHashSet<>()).add(id);
                }
                count++;
            }
            for (String folder : leftOutFolders) {
                warnings.add(id + ": the folder " + folder + " is not part of a resource pack (only assets/ is read;"
                        + " overlay folders are not supported); left out.");
            }
            counts.put(id, count);
        }
        for (Map.Entry<String, Set<String>> ns : namespaces.entrySet()) {
            if (ns.getValue().size() > 1) {
                warnings.add("the namespace '" + ns.getKey() + "' is used by " + String.join(" and ", ns.getValue())
                        + "; each source should keep to its own namespace.");
            }
        }

        // ---- merge ----
        SortedMap<String, byte[]> out = new TreeMap<>();
        Map<String, String> from = new HashMap<>();
        Map<String, JsonElement> parsed = new HashMap<>();
        Map<String, List<String>> vanilla = new TreeMap<>(Comparator.comparingInt((String s) -> rank(s, ordered))
                .thenComparing(s -> s));
        for (Map.Entry<String, List<Part>> entry : byPath.entrySet()) {
            String path = entry.getKey();
            List<Part> parts = entry.getValue();
            Kind kind = kindOf(path);
            if (kind == Kind.ITEM && parts.size() > 1) {
                JsonObject merged = mergeItem(path, parts, errors, notes);
                if (merged != null) {
                    out.put(path, ItemArt.json(merged));
                    parsed.put(path, merged);
                    String joined = String.join("+", parts.stream().map(Part::source).toList());
                    from.put(path, joined);
                    vanilla.computeIfAbsent(joined, k -> new ArrayList<>()).add(path.substring("assets/minecraft/".length()));
                    continue;
                }
            }
            if (kind != Kind.WHOLE && kind != Kind.ITEM && parts.size() > 1) {
                JsonObject merged = merge(kind, path, parts, warnings);
                out.put(path, ItemArt.json(merged));
                parsed.put(path, merged);
                from.put(path, String.join("+", parts.stream().map(Part::source).toList()));
                continue;
            }
            Part winner = parts.get(0);
            for (Part other : parts.subList(1, parts.size())) {
                if (Arrays.equals(other.bytes(), winner.bytes())) {
                    notes.add(path + " is in both " + winner.source() + " and " + other.source() + " (the same file).");
                } else {
                    errors.add("Conflict: " + path + " is in both '" + winner.source() + "' and '" + other.source()
                            + "'; using '" + winner.source() + "' (resource-pack.priority).");
                }
            }
            out.put(path, winner.bytes());
            from.put(path, winner.source());
            if (winner.json() != null) {
                parsed.put(path, winner.json());
            }
            if ((kind == Kind.WHOLE || kind == Kind.ITEM) && path.startsWith("assets/minecraft/")) {
                vanilla.computeIfAbsent(winner.source(), k -> new ArrayList<>()).add(path.substring("assets/minecraft/".length()));
            }
        }

        // ---- glyphs ----
        List<GlyphInfo> glyphs = new ArrayList<>();
        JsonArray glyphProviders = new JsonArray();
        Map<Integer, String> generated = new HashMap<>();
        for (GlyphFrom g : declared.values()) {
            Glyphs.Declared d = g.glyph();
            int code = charFor.applyAsInt(d.key());
            String location = d.texture();
            boolean present = location.startsWith("minecraft:") || out.containsKey(Glyphs.texturePath(location));
            if (code < 0) {
                warnings.add(g.source() + ": glyph " + d.key() + " has no character (all are taken); left out.");
                continue;
            }
            if (!present) {
                warnings.add(g.source() + ": glyph " + d.key() + " uses " + location + ", which is not in the pack ("
                        + Glyphs.texturePath(location) + "); it draws as a box until it is.");
            } else {
                JsonObject provider = new JsonObject();
                provider.addProperty("type", "bitmap");
                provider.addProperty("file", location);
                provider.addProperty("height", d.height());
                provider.addProperty("ascent", d.ascent());
                JsonArray chars = new JsonArray();
                chars.add(Glyphs.text(code));
                provider.add("chars", chars);
                glyphProviders.add(provider);
                generated.put(code, d.key());
            }
            glyphs.add(new GlyphInfo(d.key(), code, g.source(), location, d.height(), d.ascent(), present));
        }
        JsonObject advances = new JsonObject();
        for (Glyphs.Space space : Glyphs.SPACES.values()) {
            advances.addProperty(Glyphs.text(space.codepoint()), space.pixels());
            generated.put(space.codepoint(), space.key());
        }
        JsonObject spaceProvider = new JsonObject();
        spaceProvider.addProperty("type", "space");
        spaceProvider.add("advances", advances);
        glyphProviders.add(spaceProvider);
        JsonObject font = new JsonObject();
        JsonArray providers = new JsonArray();
        providers.addAll(glyphProviders); // first: CraftBridge's characters win over a hand-written provider
        JsonElement existing = parsed.get(GLYPH_FONT);
        if (existing != null && existing.isJsonObject() && existing.getAsJsonObject().get("providers") instanceof JsonArray own) {
            checkCharacters(own, generated, from.get(GLYPH_FONT), warnings);
            providers.addAll(own);
        }
        font.add("providers", providers);
        out.put(GLYPH_FONT, ItemArt.json(font));
        parsed.put(GLYPH_FONT, font);
        from.put(GLYPH_FONT, from.containsKey(GLYPH_FONT) ? from.get(GLYPH_FONT) + "+glyphs" : "glyphs");

        // ---- check what points where ----
        for (Map.Entry<String, JsonElement> file : new TreeMap<>(parsed).entrySet()) {
            String path = file.getKey();
            String source = from.get(path);
            if (MODEL.matcher(path).matches()) {
                checkModel(source, path, file.getValue(), out, warnings);
            } else if (ITEM_DEFINITION.matcher(path).matches()) {
                checkItemDefinition(source, path, file.getValue(), out, warnings);
            } else if (FONT.matcher(path).matches()) {
                checkFont(source, path, file.getValue(), out, warnings);
            }
        }

        // ---- pack.mcmeta and icon ----
        JsonObject pack = new JsonObject();
        pack.addProperty("description", settings.description());
        pack.add("min_format", format(settings.formatMajor(), 0));
        pack.add("max_format", format(settings.formatMajor(), settings.formatMinor()));
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);
        out.put("pack.mcmeta", ItemArt.json(mcmeta));
        if (icon != null) {
            out.put("pack.png", icon);
        }

        List<SourceInfo> infos = new ArrayList<>();
        for (Source source : ordered) {
            infos.add(new SourceInfo(source.id(), source.origin(), counts.getOrDefault(source.id(), 0),
                    glyphCounts.getOrDefault(source.id(), 0)));
        }
        return new Result(out, infos, vanilla, glyphs, errors, warnings, notes);
    }

    private static int rank(String source, List<Source> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(source)) {
                return i;
            }
        }
        return ordered.size();
    }

    private static JsonArray format(int major, int minor) {
        JsonArray array = new JsonArray();
        array.add(major);
        array.add(minor);
        return array;
    }

    // ---- reading a source ------------------------------------------------------------------

    /**
     * A pack unzipped one folder too deep ({@code MyPack/assets/...}, {@code MyPack/pack.mcmeta})
     * is read from that folder.
     */
    static SortedMap<String, byte[]> unwrap(Source source, List<String> notes) {
        SortedMap<String, byte[]> files = source.files();
        if (files.keySet().stream().anyMatch(p -> p.startsWith("assets/"))) {
            return files;
        }
        Set<String> tops = new LinkedHashSet<>();
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            tops.add(slash < 0 ? "" : path.substring(0, slash + 1));
        }
        tops.remove("");
        if (tops.size() != 1) {
            return files;
        }
        String top = tops.iterator().next();
        if (files.keySet().stream().noneMatch(p -> p.startsWith(top + "assets/"))) {
            return files;
        }
        SortedMap<String, byte[]> inner = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().startsWith(top)) {
                inner.put(file.getKey().substring(top.length()), file.getValue());
            }
        }
        notes.add(source.id() + ": the pack is inside the folder " + top + "; read from there.");
        return inner;
    }

    private static void checkMcmeta(String source, byte[] bytes, List<String> warnings) {
        JsonElement json;
        try {
            json = lenient(bytes);
        } catch (IllegalArgumentException ex) {
            return; // it is dropped anyway
        }
        if (json != null && json.isJsonObject()) {
            JsonObject root = json.getAsJsonObject();
            if (root.has("overlays")) {
                warnings.add(source + ": its pack.mcmeta has overlays, which are not supported; only assets/ is used.");
            }
            if (root.has("filter")) {
                warnings.add(source + ": its pack.mcmeta has a filter, which is not applied in the merged pack.");
            }
        }
    }

    private static void readGlyphs(String source, byte[] bytes, Map<String, GlyphFrom> declared,
                                   Map<String, Integer> glyphCounts, List<String> errors, List<String> warnings) {
        JsonElement json;
        try {
            json = lenient(bytes);
        } catch (IllegalArgumentException ex) {
            warnings.add(source + ": glyphs.json is not valid JSON (" + ex.getMessage() + "); its glyphs are left out.");
            return;
        }
        if (!json.isJsonObject()) {
            warnings.add(source + ": glyphs.json is not a JSON object; its glyphs are left out.");
            return;
        }
        Glyphs.Declarations read = Glyphs.parse(json.getAsJsonObject());
        for (String problem : read.problems()) {
            warnings.add(source + ": glyphs.json: " + problem + "; left out.");
        }
        int count = 0;
        for (Glyphs.Declared glyph : read.glyphs()) {
            if (Glyphs.SPACES.containsKey(glyph.key())) {
                warnings.add(source + ": glyphs.json: " + glyph.key() + " is one of CraftBridge's fixed spaces; left out.");
                continue;
            }
            GlyphFrom earlier = declared.get(glyph.key());
            if (earlier != null) {
                errors.add("Conflict: glyph " + glyph.key() + " is declared by both '" + earlier.source() + "' and '"
                        + source + "'; using '" + earlier.source() + "' (resource-pack.priority).");
                continue;
            }
            declared.put(glyph.key(), new GlyphFrom(source, glyph));
            count++;
        }
        glyphCounts.put(source, count);
    }

    /**
     * The file parsed strictly (as the client reads models and item definitions); null when only
     * a lenient parser reads it; throws when nothing does.
     */
    static JsonElement parse(byte[] bytes) {
        try {
            return ItemArt.parseStrictObject(bytes);
        } catch (IllegalArgumentException notStrict) {
            JsonElement lenient = lenient(bytes); // throws when it is not JSON at all
            if (!lenient.isJsonObject()) {
                throw new IllegalArgumentException("it is not a JSON object");
            }
            return null;
        }
    }

    static JsonElement lenient(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            JsonElement element = JsonParser.parseString(text);
            if (element == null || element.isJsonNull()) {
                throw new IllegalArgumentException("it is empty");
            }
            return element;
        } catch (JsonParseException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalArgumentException(String.valueOf(cause.getMessage()));
        }
    }

    // ---- merging ---------------------------------------------------------------------------

    private enum Kind { WHOLE, ITEM, FONT, ATLAS, SOUNDS, LANG }

    private static Kind kindOf(String path) {
        if (VANILLA_ITEM.matcher(path).matches()) {
            return Kind.ITEM;
        }
        if (FONT.matcher(path).matches()) {
            return Kind.FONT;
        }
        if (ATLAS.matcher(path).matches()) {
            return Kind.ATLAS;
        }
        if (SOUNDS.matcher(path).matches()) {
            return Kind.SOUNDS;
        }
        if (LANG.matcher(path).matches()) {
            return Kind.LANG;
        }
        return Kind.WHOLE;
    }

    private static JsonObject merge(Kind kind, String path, List<Part> parts, List<String> warnings) {
        JsonObject out = new JsonObject();
        switch (kind) {
            case FONT, ATLAS -> {
                String list = kind == Kind.FONT ? "providers" : "sources";
                JsonArray all = new JsonArray();
                for (Part part : parts) {
                    JsonElement value = part.json() != null && part.json().isJsonObject()
                            ? part.json().getAsJsonObject().get(list) : null;
                    if (value instanceof JsonArray array) {
                        all.addAll(array);
                    } else {
                        warnings.add(part.source() + ": " + path + " has no \"" + list + "\" list; its part is left out.");
                    }
                }
                out.add(list, all);
            }
            case SOUNDS, LANG -> {
                Map<String, List<String>> duplicates = new LinkedHashMap<>();
                Map<String, String> owner = new HashMap<>();
                for (Part part : parts) {
                    if (part.json() == null || !part.json().isJsonObject()) {
                        warnings.add(part.source() + ": " + path + " is not a JSON object; left out.");
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> e : part.json().getAsJsonObject().entrySet()) {
                        if (out.has(e.getKey())) {
                            duplicates.computeIfAbsent(owner.get(e.getKey()) + "|" + part.source(), k -> new ArrayList<>())
                                    .add(e.getKey());
                            continue;
                        }
                        out.add(e.getKey(), e.getValue());
                        owner.put(e.getKey(), part.source());
                    }
                }
                for (Map.Entry<String, List<String>> d : duplicates.entrySet()) {
                    String[] pair = d.getKey().split("\\|", 2);
                    List<String> keys = d.getValue();
                    String shown = keys.size() > 10 ? String.join(", ", keys.subList(0, 10)) + ", ..." : String.join(", ", keys);
                    warnings.add(path + ": " + keys.size() + (kind == Kind.SOUNDS ? " sound event(s)" : " key(s)")
                            + " in both '" + pair[0] + "' and '" + pair[1] + "', using '" + pair[0] + "': " + shown);
                }
            }
            default -> throw new IllegalStateException(kind.name());
        }
        return out;
    }

    /**
     * One vanilla item's definition from several sources. A definition that is a
     * {@code minecraft:select} on {@code custom_model_data} (what CraftBridge generates for custom
     * items: its cases, then Minecraft's own definition as the fallback) adds its cases; the
     * first definition of any other shape (the clock pack's {@code clock.json}) becomes the
     * fallback, so every other item of that type draws the way that source wants. Two
     * definitions of another shape are a conflict, decided by priority. Null when there is
     * nothing to merge (no select at all), so the file is handled as a whole file.
     */
    private static JsonObject mergeItem(String path, List<Part> parts, List<String> errors, List<String> notes) {
        List<Part> selects = new ArrayList<>();
        List<Part> others = new ArrayList<>();
        Integer index = null;
        for (Part part : parts) {
            JsonObject model = part.json() != null && part.json().isJsonObject()
                    && part.json().getAsJsonObject().get("model") instanceof JsonObject m ? m : null;
            Integer at = model == null ? null : customModelDataIndex(model);
            if (at != null && (index == null || index.equals(at))) {
                index = at;
                selects.add(part);
            } else {
                others.add(part);
            }
        }
        if (selects.isEmpty()) {
            return null;
        }
        Part base = others.isEmpty() ? null : others.get(0);
        for (Part other : others.subList(Math.min(1, others.size()), others.size())) {
            if (!Arrays.equals(other.bytes(), base.bytes())) {
                errors.add("Conflict: " + path + " is in both '" + base.source() + "' and '" + other.source()
                        + "'; using '" + base.source() + "' (resource-pack.priority).");
            }
        }
        JsonObject first = selects.get(0).json().getAsJsonObject();
        JsonObject out = (base != null ? base.json().getAsJsonObject() : first).deepCopy();
        JsonObject select = first.getAsJsonObject("model").deepCopy();
        JsonArray cases = new JsonArray();
        Map<String, String> whenFrom = new HashMap<>();
        for (Part part : selects) {
            JsonElement partCases = part.json().getAsJsonObject().getAsJsonObject("model").get("cases");
            if (!(partCases instanceof JsonArray array)) {
                continue;
            }
            for (JsonElement c : array) {
                String when = c instanceof JsonObject o && o.get("when") != null ? o.get("when").toString() : null;
                String earlier = when == null ? null : whenFrom.putIfAbsent(when, part.source());
                if (earlier != null) {
                    errors.add("Conflict: " + path + " case " + when + " is in both '" + earlier + "' and '"
                            + part.source() + "'; using '" + earlier + "' (resource-pack.priority).");
                    continue;
                }
                cases.add(c);
            }
        }
        select.add("cases", cases);
        if (base != null) {
            select.add("fallback", base.json().getAsJsonObject().get("model"));
            notes.add(path.substring("assets/minecraft/".length()) + ": the custom_model_data cases of "
                    + String.join(", ", selects.stream().map(Part::source).toList()) + " sit on top of "
                    + base.source() + "'s definition.");
        }
        out.add("model", select);
        return out;
    }

    /** The {@code index} of a {@code minecraft:select} on {@code custom_model_data}, or null when it is not one. */
    private static Integer customModelDataIndex(JsonObject model) {
        String type = model.get("type") instanceof JsonPrimitive t && t.isString() ? t.getAsString() : "";
        String property = model.get("property") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
        if (!(type.equals("select") || type.equals("minecraft:select"))
                || !(property.equals("custom_model_data") || property.equals("minecraft:custom_model_data"))) {
            return null;
        }
        return model.get("index") instanceof JsonPrimitive i && i.isNumber() ? i.getAsInt() : 0;
    }

    /** A hand-written provider in the glyph font using a character CraftBridge hands out. */
    private static void checkCharacters(JsonArray providers, Map<Integer, String> generated, String source,
                                        List<String> warnings) {
        Set<String> reported = new LinkedHashSet<>();
        for (JsonElement p : providers) {
            if (!(p instanceof JsonObject provider) || !(provider.get("chars") instanceof JsonArray rows)) {
                continue;
            }
            for (JsonElement row : rows) {
                if (!row.isJsonPrimitive()) {
                    continue;
                }
                row.getAsString().codePoints().forEach(cp -> {
                    String key = generated.get(cp);
                    if (key != null) {
                        reported.add(Glyphs.hex(cp) + " (" + key + ")");
                    }
                });
            }
        }
        if (!reported.isEmpty()) {
            warnings.add(source + ": " + GLYPH_FONT + " draws characters CraftBridge assigns to glyphs, so its own"
                    + " drawing of them is hidden: " + String.join(", ", reported) + ". Declare the glyph in glyphs.json"
                    + " instead.");
        }
    }

    // ---- checking references ---------------------------------------------------------------

    /** {@code ns:path} or {@code path} (minecraft), as {@code [namespace, path]}; null when it is not a resource location. */
    static String[] location(String value) {
        int colon = value.indexOf(':');
        String ns = colon < 0 ? "minecraft" : value.substring(0, colon);
        String path = colon < 0 ? value : value.substring(colon + 1);
        if (!NAMESPACE.matcher(ns).matches() || !RESOURCE_PATH.matcher(path).matches()) {
            return null;
        }
        return new String[]{ns, path};
    }

    /**
     * Whether {@code assets/<ns>/<folder>/<path><suffix>} is in the pack. Minecraft's own assets are
     * not in the pack, so a reference into {@code minecraft:} is taken as one of them unless the
     * pack has the file.
     */
    private static boolean exists(String[] loc, String folder, String suffix, Map<String, byte[]> files) {
        return loc[0].equals("minecraft") || files.containsKey("assets/" + loc[0] + "/" + folder + "/" + loc[1] + suffix);
    }

    private static void checkModel(String source, String path, JsonElement json, Map<String, byte[]> files,
                                   List<String> warnings) {
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject model = json.getAsJsonObject();
        if (model.get("parent") instanceof JsonPrimitive p && p.isString()) {
            String parent = p.getAsString();
            String[] loc = location(parent);
            if (loc == null) {
                warnings.add(source + ": " + path + " has the parent '" + parent + "', which is not a model name.");
            } else if (!loc[1].startsWith("builtin/") && !exists(loc, "models", ".json", files)) {
                warnings.add(source + ": " + path + " has the parent " + parent + ", which is not in the pack.");
            }
        }
        if (model.get("textures") instanceof JsonObject textures) {
            for (Map.Entry<String, JsonElement> t : textures.entrySet()) {
                String sprite = null;
                if (t.getValue() instanceof JsonPrimitive s && s.isString()) {
                    sprite = s.getAsString();
                } else if (t.getValue() instanceof JsonObject o && o.get("sprite") instanceof JsonPrimitive s && s.isString()) {
                    sprite = s.getAsString();
                }
                if (sprite == null || sprite.startsWith("#")) {
                    continue;
                }
                String[] loc = location(sprite);
                if (loc == null) {
                    warnings.add(source + ": " + path + " texture \"" + t.getKey() + "\" is '" + sprite
                            + "', which is not a texture name.");
                } else if (!exists(loc, "textures", ".png", files)) {
                    warnings.add(source + ": " + path + " uses the texture " + sprite + ", which is not in the pack ("
                            + "assets/" + loc[0] + "/textures/" + loc[1] + ".png).");
                }
            }
        }
    }

    private static void checkItemDefinition(String source, String path, JsonElement json, Map<String, byte[]> files,
                                            List<String> warnings) {
        Set<String> missing = new LinkedHashSet<>();
        walk(json, node -> {
            String type = node.get("type") instanceof JsonPrimitive t && t.isString() ? t.getAsString() : "";
            String ref = null;
            if ((type.equals("model") || type.equals("minecraft:model")) && node.get("model") instanceof JsonPrimitive m
                    && m.isString()) {
                ref = m.getAsString();
            } else if ((type.equals("special") || type.equals("minecraft:special")) && node.get("base") instanceof JsonPrimitive b
                    && b.isString()) {
                ref = b.getAsString();
            }
            if (ref != null) {
                String[] loc = location(ref);
                if (loc == null || !exists(loc, "models", ".json", files)) {
                    missing.add(ref);
                }
            }
        });
        if (!missing.isEmpty()) {
            warnings.add(source + ": " + path + " uses the model(s) " + String.join(", ", missing)
                    + ", not in the pack; those items draw as the missing-texture cube.");
        }
    }

    private static void checkFont(String source, String path, JsonElement json, Map<String, byte[]> files,
                                  List<String> warnings) {
        if (!json.isJsonObject() || !(json.getAsJsonObject().get("providers") instanceof JsonArray providers)) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        for (JsonElement p : providers) {
            if (p instanceof JsonObject provider && provider.get("type") instanceof JsonPrimitive type
                    && type.getAsString().replace("minecraft:", "").equals("bitmap")
                    && provider.get("file") instanceof JsonPrimitive file && file.isString()) {
                String[] loc = location(file.getAsString());
                if (loc == null || !exists(loc, "textures", "", files)) {
                    missing.add(file.getAsString());
                }
            }
        }
        if (!missing.isEmpty()) {
            warnings.add(source + ": " + path + " draws from " + String.join(", ", missing)
                    + ", not in the pack; the client drops those characters.");
        }
    }

    private static void walk(JsonElement element, java.util.function.Consumer<JsonObject> visit) {
        if (element instanceof JsonObject object) {
            visit.accept(object);
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                walk(e.getValue(), visit);
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement e : array) {
                walk(e, visit);
            }
        }
    }
}
