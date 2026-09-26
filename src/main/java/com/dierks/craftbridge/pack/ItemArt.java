package com.dierks.craftbridge.pack;

import com.dierks.craftbridge.items.ModelTags;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Custom item art: turns the files an admin drops into {@code plugins/CraftBridge/pack/items/}
 * into resource pack files. No Bukkit here, so the whole thing is tested on plain maps.
 *
 * <p>Every custom item carries {@code craftbridge:item/<id>} at index 0 of its
 * {@code custom_model_data} strings (see {@link ModelTags}). For each custom item with
 * {@code <id>.png} (or a model of its own, {@code <id>.json}) this writes:
 * <ul>
 *   <li>the texture, {@code assets/craftbridge/textures/item/<id>.png} (with its
 *       {@code .mcmeta} when animated);</li>
 *   <li>the model, {@code assets/craftbridge/models/item/<id>.json}: the admin's
 *       {@code <id>.json} verbatim, else a flat item on the base's own template (a sword is
 *       still held like a sword), or a cube for a block item, whose texture then goes to
 *       {@code textures/block/<id>.png} in the blocks atlas, the way vanilla draws block items;</li>
 *   <li>the base item's definition, {@code assets/minecraft/items/<base>.json}: one
 *       {@code minecraft:select} on {@code custom_model_data} with a case per textured custom
 *       item on that base and <b>Minecraft's own definition, verbatim, as the fallback</b>
 *       ({@link VanillaItems}). This file draws every item of that type, so anything that is
 *       not one of these custom items looks exactly as it does without the pack.</li>
 * </ul>
 * CraftBridge's own {@code crafting_table.json} and {@code barrel.json} gain the cases instead
 * of being replaced. A definition the admin put in {@code pack/overrides/java/} wins: that base
 * is not generated.
 *
 * <p>Player-head custom items keep their skin and never get pack art.
 */
public final class ItemArt {

    /** The art folder, below {@code plugins/CraftBridge/pack/}. */
    public static final String FOLDER = "items";

    /** Texture widths accepted: square at these sizes, or a vertical animation strip of square frames. */
    public static final List<Integer> SIZES = List.of(16, 32, 64, 128);

    static final String ITEMS_PATH = "assets/minecraft/items/";
    static final String MODELS_PATH = "assets/craftbridge/models/item/";
    static final String TEXTURES_PATH = "assets/craftbridge/textures/";

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ItemArt() {
    }

    /**
     * A custom item as the pack sees it.
     *
     * @param base the base item's id, e.g. {@code dried_kelp}
     * @param head a player-head item, which keeps its skin
     */
    public record Item(String id, String base, boolean head) {
    }

    /**
     * A custom item that has art in the pack.
     *
     * @param width       texture width, 0 when a custom model brings no texture of its own
     * @param height      texture height (more than {@code width} for an animation strip)
     * @param customModel the model is the admin's {@code <id>.json}, not a generated one
     * @param definition  where the base's item definition comes from, for {@code /craftbridge pack}
     * @param png         the texture, or null (the Bedrock export uses it as the icon)
     */
    public record Textured(String id, String base, int width, int height, boolean customModel,
                           String definition, byte[] png) {

        /** {@code 16x16}, {@code 16x64 animated}, or {@code no texture of its own}. */
        public String size() {
            if (width == 0) {
                return "no texture of its own";
            }
            return width + "x" + height + (height > width ? " animated" : "");
        }
    }

    /** A custom item, or a file in the art folder, that was left out, and why. */
    public record Skipped(String name, String reason) {
    }

    /**
     * What a build produced.
     *
     * @param files    pack files to add on top of CraftBridge's own (overrides still go on top of these)
     * @param warnings problems that did not keep an item out, one line each
     */
    public record Result(SortedMap<String, byte[]> files, List<Textured> textured, List<Skipped> skipped,
                         List<String> warnings) {

        public static Result none() {
            return new Result(new TreeMap<>(), List.of(), List.of(), List.of());
        }

        /** The start/reload log line: {@code N custom items have art (...), M skipped (...)}. */
        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append(textured.size()).append(textured.size() == 1 ? " custom item has art" : " custom items have art");
            if (!textured.isEmpty()) {
                out.append(" (").append(String.join(", ", textured.stream().map(Textured::id).toList())).append(')');
            }
            out.append(", ").append(skipped.size()).append(" skipped");
            if (!skipped.isEmpty()) {
                out.append(" (").append(String.join("; ",
                        skipped.stream().map(s -> s.name() + ": " + s.reason()).toList())).append(')');
            }
            return out.toString();
        }
    }

    /** A decoded texture's size. */
    public record Png(int width, int height) {
    }

    /** What the custom item editor shows about one item's art. */
    public record Status(boolean usable, String text) {
    }

    // ---- building ------------------------------------------------------------------------

    /**
     * Build the art files.
     *
     * @param items            every custom item
     * @param folder           the art folder's files, by path relative to it
     * @param ownPack          CraftBridge's own pack files (from the jar, without overrides)
     * @param overridden       every path the admin's {@code pack/overrides/java/} provides
     * @param vanilla          Minecraft's item definitions for this server's version, or null when
     *                         this CraftBridge has none for it (then no item gets art)
     * @param minecraftVersion the server's Minecraft version, for messages
     */
    public static Result build(Collection<Item> items, Map<String, byte[]> folder, Map<String, byte[]> ownPack,
                               Set<String> overridden, VanillaItems vanilla, String minecraftVersion) {
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Folder art = Folder.read(folder, skipped);

        Map<String, Item> byId = new TreeMap<>();
        for (Item item : items) {
            byId.put(item.id(), item);
        }
        Set<String> used = new LinkedHashSet<>();

        // 1. Each item on its own: its texture, its model, and that nothing it writes clashes.
        Map<String, Accepted> accepted = new TreeMap<>();
        for (Item item : byId.values()) {
            String id = item.id();
            byte[] png = art.png.get(id);
            byte[] model = art.model.get(id);
            if (png == null && model == null) {
                continue;
            }
            used.add(id);
            if (item.head()) {
                skipped.add(new Skipped(id, "player heads keep their skin, so " + art.names(id) + " is not used"));
                continue;
            }
            if (vanilla == null) {
                skipped.add(new Skipped(id, "this CraftBridge has no vanilla item table for Minecraft "
                        + minecraftVersion + ", so custom item art is off until it is updated"));
                continue;
            }
            VanillaItems.Kind kind = vanilla.kind(item.base());
            if (kind == null) {
                skipped.add(new Skipped(id, "Minecraft " + vanilla.version() + " has no item definition for "
                        + item.base()));
                continue;
            }
            byte[] mcmeta = art.mcmeta.get(id);
            Png size = null;
            if (png != null) {
                String problem = pngProblem(art.name(id + ".png"), png, mcmeta);
                if (problem != null) {
                    skipped.add(new Skipped(id, problem));
                    continue;
                }
                size = readPng(png);
            }
            JsonObject customModel = null;
            if (model != null) {
                try {
                    customModel = parseObject(model);
                } catch (IllegalArgumentException ex) {
                    skipped.add(new Skipped(id, art.name(id + ".json") + " is not a JSON model (" + ex.getMessage() + ")"));
                    continue;
                }
            }

            SortedMap<String, byte[]> files = new TreeMap<>();
            String textureFolder;
            if (customModel != null) {
                files.put(MODELS_PATH + id + ".json", model);
                textureFolder = "item";
            } else if (kind == VanillaItems.Kind.BLOCK) {
                files.put(MODELS_PATH + id + ".json", json(cubeModel(id)));
                textureFolder = "block";
            } else {
                String parent = kind == VanillaItems.Kind.ITEM ? vanilla.itemParent(item.base()) : VanillaItems.GENERATED;
                files.put(MODELS_PATH + id + ".json", json(flatModel(parent, id)));
                textureFolder = "item";
            }
            if (png != null) {
                putTexture(files, textureFolder, id, png, mcmeta);
            }
            String clash = files.keySet().stream().filter(ownPack::containsKey).findFirst().orElse(null);
            if (clash != null) {
                skipped.add(new Skipped(id, "its files would replace CraftBridge's own " + clash));
                continue;
            }
            accepted.put(id, new Accepted(item, size, customModel, files, png));
        }

        // 2. One item definition per base item.
        Map<String, List<String>> byBase = new TreeMap<>();
        for (Accepted a : accepted.values()) {
            byBase.computeIfAbsent(a.item.base(), b -> new ArrayList<>()).add(a.item.id());
        }
        SortedMap<String, byte[]> out = new TreeMap<>();
        Map<String, String> definitionOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byBase.entrySet()) {
            String base = entry.getKey();
            List<String> ids = entry.getValue();
            String path = ITEMS_PATH + base + ".json";
            if (overridden.contains(path)) {
                warnings.add("pack/overrides/java/" + path + " replaces the item definition CraftBridge would write"
                        + " for " + base + ", so it must select " + String.join(", ", ids.stream().map(ModelTags::of).toList())
                        + " itself.");
                ids.forEach(id -> definitionOf.put(id, "your override"));
            } else if (ownPack.containsKey(path)) {
                byte[] merged = merge(ownPack.get(path), ids);
                if (merged == null) {
                    for (String id : ids) {
                        accepted.remove(id);
                        skipped.add(new Skipped(id, "CraftBridge's own " + path + " is not a select on custom_model_data"));
                    }
                    continue;
                }
                out.put(path, merged);
                ids.forEach(id -> definitionOf.put(id, "shared with CraftBridge's " + base + " model"));
            } else {
                out.put(path, json(definition(vanilla.definition(base), ids)));
                ids.forEach(id -> definitionOf.put(id, "generated"));
            }
        }

        // 3. The accepted items' own files, and the extra textures their custom models name.
        List<Textured> textured = new ArrayList<>();
        for (Accepted a : accepted.values()) {
            out.putAll(a.files);
            textured.add(new Textured(a.item.id(), a.item.base(), a.size == null ? 0 : a.size.width(),
                    a.size == null ? 0 : a.size.height(), a.customModel != null, definitionOf.get(a.item.id()), a.png));
            if (a.customModel != null) {
                addExtraTextures(a.item.id(), a.customModel, art, out, used, warnings);
            }
        }

        // 4. Files nothing used: usually a typo in the name, so say which.
        for (String stem : art.stems()) {
            if (used.contains(stem)) {
                continue;
            }
            if (art.png.containsKey(stem) || art.model.containsKey(stem)) {
                skipped.add(new Skipped(art.names(stem), "no custom item has the id " + stem));
            } else {
                skipped.add(new Skipped(art.name(stem + ".png.mcmeta"), "there is no " + stem + ".png to animate"));
            }
        }
        return new Result(out, List.copyOf(textured), List.copyOf(skipped), List.copyOf(warnings));
    }

    private record Accepted(Item item, Png size, JsonObject customModel, SortedMap<String, byte[]> files, byte[] png) {
    }

    /** The texture, and its animation beside it. */
    private static void putTexture(Map<String, byte[]> files, String folder, String name, byte[] png, byte[] mcmeta) {
        files.put(TEXTURES_PATH + folder + "/" + name + ".png", png);
        if (mcmeta != null) {
            files.put(TEXTURES_PATH + folder + "/" + name + ".png.mcmeta", mcmeta);
        }
    }

    /**
     * Textures a custom model names as {@code craftbridge:item/<name>} come from
     * {@code <name>.png} in the art folder. A reference without a namespace is Minecraft's, which
     * is the usual slip in a Blockbench export, so it is pointed out when the folder has that file.
     */
    private static void addExtraTextures(String id, JsonObject model, Folder art, Map<String, byte[]> out,
                                         Set<String> used, List<String> warnings) {
        JsonElement textures = model.get("textures");
        if (textures == null || !textures.isJsonObject()) {
            return;
        }
        String file = art.name(id + ".json");
        for (Map.Entry<String, JsonElement> texture : textures.getAsJsonObject().entrySet()) {
            if (!texture.getValue().isJsonPrimitive()) {
                continue;
            }
            String ref = texture.getValue().getAsString();
            if (ref.startsWith("#")) {
                continue;
            }
            if (ref.startsWith(ModelTags.PREFIX)) {
                String name = ref.substring(ModelTags.PREFIX.length());
                byte[] png = art.png.get(name);
                if (png == null) {
                    warnings.add(file + " uses " + ref + ", but pack/items/ has no " + name + ".png.");
                    continue;
                }
                String problem = pngProblem(art.name(name + ".png"), png, art.mcmeta.get(name));
                if (problem != null) {
                    warnings.add(file + " uses " + ref + ", which is left out: " + problem);
                    continue;
                }
                putTexture(out, "item", name, png, art.mcmeta.get(name));
                used.add(name);
            } else if (!ref.contains(":")) {
                String name = ref.startsWith("item/") ? ref.substring("item/".length()) : ref;
                if (art.png.containsKey(name)) {
                    warnings.add(file + " uses \"" + ref + "\", which Minecraft reads as minecraft:" + ref
                            + "; write " + ModelTags.PREFIX + name + " to use " + name + ".png.");
                }
            }
        }
    }

    // ---- the JSON ------------------------------------------------------------------------

    /** A block item's model: a cube with the texture on every face, from the blocks atlas. */
    static JsonObject cubeModel(String id) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:block/cube_all");
        JsonObject textures = new JsonObject();
        textures.addProperty("all", "craftbridge:block/" + id);
        model.add("textures", textures);
        return model;
    }

    /** A flat item model on a layer-0 template. */
    static JsonObject flatModel(String parent, String id) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", parent);
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", ModelTags.of(id));
        model.add("textures", textures);
        return model;
    }

    private static JsonObject caseFor(String id) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", ModelTags.of(id));
        JsonObject when = new JsonObject();
        when.addProperty("when", ModelTags.of(id));
        when.add("model", model);
        return when;
    }

    /**
     * The base's new definition: vanilla's, with its {@code model} replaced by a select whose
     * fallback is that {@code model}. Every other top-level key (e.g. {@code swap_animation_scale})
     * stays, in its place.
     */
    static JsonObject definition(JsonObject vanilla, List<String> ids) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : vanilla.entrySet()) {
            if (!entry.getKey().equals("model")) {
                out.add(entry.getKey(), entry.getValue());
                continue;
            }
            JsonObject select = new JsonObject();
            select.addProperty("type", "minecraft:select");
            select.addProperty("property", "minecraft:custom_model_data");
            select.addProperty("index", 0);
            JsonArray cases = new JsonArray();
            ids.forEach(id -> cases.add(caseFor(id)));
            select.add("cases", cases);
            select.add("fallback", entry.getValue());
            out.add("model", select);
        }
        return out;
    }

    /** CraftBridge's own select with the custom items' cases added after its own, or null when it is not such a select. */
    static byte[] merge(byte[] own, List<String> ids) {
        JsonObject root;
        try {
            root = parseObject(own);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        JsonElement modelElement = root.get("model");
        if (modelElement == null || !modelElement.isJsonObject()) {
            return null;
        }
        JsonObject model = modelElement.getAsJsonObject();
        boolean select = "minecraft:select".equals(string(model, "type"))
                && "minecraft:custom_model_data".equals(string(model, "property"))
                && (!model.has("index") || (model.get("index").isJsonPrimitive() && model.get("index").getAsInt() == 0))
                && model.has("cases") && model.get("cases").isJsonArray();
        if (!select) {
            return null;
        }
        JsonArray cases = model.getAsJsonArray("cases");
        ids.forEach(id -> cases.add(caseFor(id)));
        return json(root);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static JsonObject parseObject(byte[] bytes) {
        JsonElement element;
        try {
            element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalArgumentException(String.valueOf(cause.getMessage()));
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("it is not a JSON object");
        }
        return element.getAsJsonObject();
    }

    /** Pretty-printed, so an admin can read the pack; the same tree always gives the same bytes. */
    static byte[] json(JsonElement element) {
        return (GSON.toJson(element) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // ---- textures ------------------------------------------------------------------------

    /** The size of a PNG, decoded in full so a truncated file is caught; throws with the reason otherwise. */
    public static Png readPng(byte[] png) {
        if (png.length < PNG_SIGNATURE.length || !Arrays.equals(Arrays.copyOf(png, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            throw new IllegalArgumentException("it is not a PNG file");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) {
            throw new IllegalArgumentException("this Java has no PNG reader");
        }
        ImageReader reader = readers.next();
        // A memory-cached stream: ImageIO's default would cache to a temp file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(in);
            BufferedImage image = reader.read(0);
            return new Png(image.getWidth(), image.getHeight());
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("the PNG cannot be read (" + ex.getMessage() + ")");
        } finally {
            reader.dispose();
        }
    }

    /**
     * Null when the texture is usable: a PNG that decodes, square at 16, 32, 64 or 128 pixels, or
     * a vertical strip of such frames (height a multiple of width) with an {@code .mcmeta}
     * animating it. Otherwise the reason, naming the file.
     */
    public static String pngProblem(String fileName, byte[] png, byte[] mcmeta) {
        Png size;
        try {
            size = readPng(png);
        } catch (IllegalArgumentException ex) {
            return fileName + ": " + ex.getMessage();
        }
        int w = size.width();
        int h = size.height();
        String dims = w + "x" + h;
        if (!SIZES.contains(w)) {
            return fileName + " is " + dims + ": make it 16, 32, 64 or 128 pixels wide";
        }
        if (h != w) {
            if (h < w || h % w != 0) {
                return fileName + " is " + dims + ": it must be square, or a strip of square frames (height a multiple of width)";
            }
            if (mcmeta == null) {
                return fileName + " is a " + dims + " strip: add " + fileName + ".mcmeta to animate it";
            }
        }
        if (mcmeta != null) {
            try {
                JsonObject meta = parseObject(mcmeta);
                if (!meta.has("animation") || !meta.get("animation").isJsonObject()) {
                    return fileName + ".mcmeta has no \"animation\" section";
                }
            } catch (IllegalArgumentException ex) {
                return fileName + ".mcmeta is not valid JSON (" + ex.getMessage() + ")";
            }
        }
        return null;
    }

    // ---- the editor's line ---------------------------------------------------------------

    /**
     * One item's art as the custom item editor shows it: null when the folder has none for it,
     * else {@code found (16x16)}, {@code custom model <id>.json}, or what is wrong.
     */
    public static Status status(Path folder, String id) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : PackFiles.fromDirectory(folder).entrySet()) {
            String lower = file.getKey().toLowerCase(Locale.ROOT);
            if (lower.equals(id + ".png") || lower.equals(id + ".png.mcmeta") || lower.equals(id + ".json")) {
                files.putIfAbsent(lower, file.getValue());
            }
        }
        byte[] png = files.get(id + ".png");
        byte[] model = files.get(id + ".json");
        if (png == null && model == null) {
            return null;
        }
        if (png != null) {
            String problem = pngProblem(id + ".png", png, files.get(id + ".png.mcmeta"));
            if (problem != null) {
                return new Status(false, problem);
            }
        }
        if (model != null) {
            try {
                parseObject(model);
            } catch (IllegalArgumentException ex) {
                return new Status(false, id + ".json is not a JSON model (" + ex.getMessage() + ")");
            }
        }
        if (png == null) {
            return new Status(true, "custom model " + id + ".json");
        }
        Png size = readPng(png);
        String text = "found (" + size.width() + "x" + size.height() + (size.height() > size.width() ? ", animated" : "") + ")";
        return new Status(true, model == null ? text : text + " with model " + id + ".json");
    }

    // ---- the folder ----------------------------------------------------------------------

    /** The art folder's files by kind, keyed by lower-case stem (file names from Windows keep their case). */
    private static final class Folder {
        final Map<String, byte[]> png = new TreeMap<>();
        final Map<String, byte[]> mcmeta = new TreeMap<>();
        final Map<String, byte[]> model = new TreeMap<>();
        /** Lower-case name -> the name as written, for messages. */
        final Map<String, String> written = new TreeMap<>();

        static Folder read(Map<String, byte[]> files, List<Skipped> skipped) {
            Folder folder = new Folder();
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                String name = file.getKey();
                if (name.contains("/")) {
                    skipped.add(new Skipped(name, "only files directly in pack/items/ are read"));
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("readme.txt")) {
                    continue;
                }
                if (folder.written.containsKey(lower)) {
                    skipped.add(new Skipped(name, "the same name as " + folder.written.get(lower) + " apart from upper/lower case"));
                    continue;
                }
                Map<String, byte[]> kind;
                String stem;
                if (lower.endsWith(".png.mcmeta")) {
                    kind = folder.mcmeta;
                    stem = lower.substring(0, lower.length() - ".png.mcmeta".length());
                } else if (lower.endsWith(".png")) {
                    kind = folder.png;
                    stem = lower.substring(0, lower.length() - ".png".length());
                } else if (lower.endsWith(".json")) {
                    kind = folder.model;
                    stem = lower.substring(0, lower.length() - ".json".length());
                } else {
                    skipped.add(new Skipped(name, "not a .png, .png.mcmeta or .json file"));
                    continue;
                }
                if (!stem.matches("[a-z0-9_.-]+")) {
                    skipped.add(new Skipped(name, "file names may only use letters, digits, _, - and ."));
                    continue;
                }
                folder.written.put(lower, name);
                kind.put(stem, file.getValue());
            }
            return folder;
        }

        /** Every stem with a file of any kind, sorted. */
        Set<String> stems() {
            Set<String> out = new java.util.TreeSet<>();
            out.addAll(png.keySet());
            out.addAll(mcmeta.keySet());
            out.addAll(model.keySet());
            return out;
        }

        /** A file's name as the admin wrote it. */
        String name(String lower) {
            return written.getOrDefault(lower, lower);
        }

        /** The item's files as written, e.g. {@code Founders_Skull.png and founders_skull.json}. */
        String names(String stem) {
            List<String> names = new ArrayList<>();
            for (String suffix : List.of(".png", ".png.mcmeta", ".json")) {
                if (written.containsKey(stem + suffix)) {
                    names.add(written.get(stem + suffix));
                }
            }
            return String.join(" and ", names);
        }
    }
}
