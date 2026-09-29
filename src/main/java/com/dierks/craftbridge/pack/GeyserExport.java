package com.dierks.craftbridge.pack;

import com.dierks.craftbridge.items.ModelTags;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@code /craftbridge geyser export}: writes the Bedrock pack and the Geyser mappings into
 * {@code plugins/CraftBridge/geyser/}, and copies them into Geyser's own folders when Geyser
 * runs on this server, or into {@code bedrock.geyser-folder} when it runs on a proxy on the
 * same machine. No Bukkit here, so it is tested on a temporary directory.
 *
 * <p>Custom items with pack art get a Bedrock icon too: their PNG (the first frame of an
 * animation) in the pack, an {@code item_texture.json} entry, and a v2 mapping on the base
 * item that matches {@code craftbridge:item/<id>} at index 0 of the custom model data, the same
 * shape as the Linked Workbench's mapping. The Bedrock pack's manifest version then follows its
 * content, because Bedrock clients keep a cached pack with the same version.
 */
public final class GeyserExport {

    public static final String MCPACK = "CraftBridge.mcpack";
    public static final String MAPPINGS = "craftbridge_mappings.json";
    public static final String DISPLAY_MAPPINGS = "geyserdisplayentity_craftbridge.yml";
    /** What the display-entity mapping is called inside GeyserDisplayEntity's Mappings folder. */
    static final String DISPLAY_MAPPINGS_TARGET = "craftbridge.yml";

    /** Geyser's data folder on Paper/Spigot, next to plugins/CraftBridge. */
    static final String GEYSER_FOLDER = "Geyser-Spigot";
    /** The GeyserDisplayEntity extension's data folder inside Geyser's. */
    static final String DISPLAY_EXTENSION = "extensions/geyserdisplayentity";

    /** Where custom item icons go in the Bedrock pack, and their prefix in item_texture.json. */
    static final String ICONS = "textures/items/craftbridge/items/";
    static final String ICON_KEY = "craftbridge.item.";

    private GeyserExport() {
    }

    /**
     * A custom item with pack art, as the Bedrock side needs it.
     *
     * @param base        the base item's id, e.g. {@code dried_kelp}
     * @param displayName the item's name as plain text
     * @param png         its Java texture
     */
    public record BedrockItem(String id, String base, String displayName, byte[] png) {
    }

    /** The export without custom item icons, looking for Geyser next to CraftBridge. */
    public static List<String> export(Map<String, byte[]> bedrockPack, byte[] mappings, byte[] displayMappings,
                                      Path outDir, Path pluginsDir) throws IOException {
        return export(bedrockPack, mappings, displayMappings, List.of(), outDir, pluginsDir, null);
    }

    /**
     * @param bedrockPack     files of the Bedrock pack (manifest.json at the root)
     * @param mappings        the Geyser v2 item mapping json
     * @param displayMappings the GeyserDisplayEntity mapping yml
     * @param items           custom items with pack art, to get Bedrock icons
     * @param outDir          plugins/CraftBridge/geyser
     * @param pluginsDir      the server's plugins folder, where Geyser-Spigot may live
     * @param geyserFolder    {@code bedrock.geyser-folder}, or null: Geyser's folder elsewhere on this machine
     * @return one line per thing done, for the command sender and the log
     */
    public static List<String> export(Map<String, byte[]> bedrockPack, byte[] mappings, byte[] displayMappings,
                                      List<BedrockItem> items, Path outDir, Path pluginsDir, Path geyserFolder)
            throws IOException {
        List<String> done = new ArrayList<>();
        if (!items.isEmpty()) {
            SortedMap<String, byte[]> pack = new TreeMap<>(bedrockPack);
            mappings = addItems(pack, mappings, items, done);
            bedrockPack = pack;
        }
        Files.createDirectories(outDir);
        byte[] mcpack = PackFiles.zip(bedrockPack);
        write(outDir.resolve(MCPACK), mcpack);
        write(outDir.resolve(MAPPINGS), mappings);
        write(outDir.resolve(DISPLAY_MAPPINGS), displayMappings);
        done.add("Wrote " + MCPACK + ", " + MAPPINGS + " and " + DISPLAY_MAPPINGS + " to " + outDir);

        if (geyserFolder != null) {
            if (!Files.isDirectory(geyserFolder)) {
                done.add("bedrock.geyser-folder is " + geyserFolder + ", which is not a folder, so nothing was copied"
                        + " into Geyser: fix the path, or copy " + MCPACK + " into Geyser's packs/ folder and " + MAPPINGS
                        + " into its custom_mappings/ folder yourself.");
                done.add(RESTART_AFTER_COPY);
                return done;
            }
            copyInto(geyserFolder, geyserFolder.toString(), mcpack, mappings, displayMappings, done);
            done.add("Restart the proxy Geyser runs on: Geyser only loads new packs and mappings when it starts."
                    + " Geyser's config needs enable-custom-content: true.");
            return done;
        }
        Path geyser = pluginsDir.resolve(GEYSER_FOLDER);
        if (!Files.isDirectory(geyser)) {
            done.add("No " + GEYSER_FOLDER + " folder here (Geyser may run on the proxy): copy " + MCPACK
                    + " into Geyser's packs/ folder and " + MAPPINGS + " into its custom_mappings/ folder yourself,"
                    + " or set bedrock.geyser-folder to Geyser's folder.");
            done.add(RESTART_AFTER_COPY);
            return done;
        }
        copyInto(geyser, GEYSER_FOLDER, mcpack, mappings, displayMappings, done);
        done.add("Restart the server (Geyser reads packs and mappings only at startup). Geyser's config needs"
                + " enable-custom-content: true.");
        return done;
    }

    private static final String RESTART_AFTER_COPY = "After copying, restart the server or proxy Geyser runs on:"
            + " Geyser only loads new packs and mappings when it starts. Geyser's config needs"
            + " enable-custom-content: true.";

    private static void copyInto(Path geyser, String name, byte[] mcpack, byte[] mappings, byte[] displayMappings,
                                 List<String> done) throws IOException {
        write(geyser.resolve("packs").resolve(MCPACK), mcpack);
        write(geyser.resolve("custom_mappings").resolve(MAPPINGS), mappings);
        done.add("Copied them into " + name + "/packs and " + name + "/custom_mappings.");
        Path extension = geyser.resolve(DISPLAY_EXTENSION);
        if (Files.isDirectory(extension)) {
            write(extension.resolve("Mappings").resolve(DISPLAY_MAPPINGS_TARGET), displayMappings);
            done.add("Copied the display mapping into " + name + "/" + DISPLAY_EXTENSION + "/Mappings.");
        } else {
            done.add("GeyserDisplayEntity is not installed, so Bedrock players see the items but not the block models.");
        }
    }

    // ---- custom item icons -----------------------------------------------------------

    /**
     * Put the custom items' icons into the pack and their definitions into the mappings, and
     * give the pack a version of its own. Returns the new mappings.
     */
    static byte[] addItems(SortedMap<String, byte[]> pack, byte[] mappings, List<BedrockItem> items, List<String> done) {
        JsonObject mapping = ItemArt.parseObject(mappings);
        JsonObject mapped = mapping.getAsJsonObject("items");
        JsonObject atlas = pack.containsKey("textures/item_texture.json")
                ? ItemArt.parseObject(pack.get("textures/item_texture.json")) : new JsonObject();
        if (!atlas.has("resource_pack_name")) {
            atlas.addProperty("resource_pack_name", "CraftBridge");
            atlas.addProperty("texture_name", "atlas.items");
        }
        if (!atlas.has("texture_data") || !atlas.get("texture_data").isJsonObject()) {
            atlas.add("texture_data", new JsonObject());
        }
        JsonObject textures = atlas.getAsJsonObject("texture_data");
        List<String> added = new ArrayList<>();
        for (BedrockItem item : items.stream().sorted((a, b) -> a.id().compareTo(b.id())).toList()) {
            byte[] icon;
            try {
                icon = firstFrame(item.png());
            } catch (IOException | RuntimeException ex) {
                done.add(item.id() + ": its PNG cannot be read (" + ex.getMessage() + "), so Bedrock players see the base item.");
                continue;
            }
            String path = iconPath(item.id());
            pack.put(path + ".png", icon);
            JsonObject texture = new JsonObject();
            JsonArray paths = new JsonArray();
            paths.add(path);
            texture.add("textures", paths);
            textures.add(ICON_KEY + item.id(), texture);

            String javaItem = "minecraft:" + item.base();
            if (!mapped.has(javaItem) || !mapped.get(javaItem).isJsonArray()) {
                mapped.add(javaItem, new JsonArray());
            }
            mapped.getAsJsonArray(javaItem).add(definition(item));
            added.add(item.id());
        }
        pack.put("textures/item_texture.json", ItemArt.json(atlas));
        pack.put("manifest.json", versioned(pack));
        if (!added.isEmpty()) {
            done.add("Bedrock icons for " + added.size() + " custom item(s): " + String.join(", ", added) + ".");
        }
        return ItemArt.json(mapping);
    }

    /**
     * Where an item's icon goes, without {@code .png}. Consoles cannot load a path of 80
     * characters or more from a pack, and an id may have 64, so a long id gets a short name.
     */
    static String iconPath(String id) {
        String path = ICONS + id;
        if (path.length() + ".png".length() < MAX_PATH) {
            return path;
        }
        return ICONS + PackFiles.sha1(id.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** Geyser warns that a pack path this long or longer fails on some Bedrock platforms. */
    static final int MAX_PATH = 80;

    /** A Geyser v2 definition: the base item with our string at index 0 becomes the Bedrock item. */
    static JsonObject definition(BedrockItem item) {
        JsonObject definition = new JsonObject();
        definition.addProperty("type", "definition");
        definition.addProperty("model", "minecraft:" + item.base());
        definition.addProperty("bedrock_identifier", "craftbridge:item_" + item.id());
        definition.addProperty("display_name", item.displayName());
        JsonObject predicate = new JsonObject();
        predicate.addProperty("type", "match");
        predicate.addProperty("property", "custom_model_data");
        predicate.addProperty("value", ModelTags.of(item.id()));
        predicate.addProperty("index", 0);
        definition.add("predicate", predicate);
        JsonObject options = new JsonObject();
        options.addProperty("icon", ICON_KEY + item.id());
        options.addProperty("creative_category", "items");
        definition.add("bedrock_options", options);
        return definition;
    }

    /** The PNG as it is when square, else its top square: Bedrock icons do not animate. */
    static byte[] firstFrame(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("not an image");
        }
        if (image.getHeight() <= image.getWidth()) {
            return png;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image.getSubimage(0, 0, image.getWidth(), image.getWidth()), "png", out);
        return out.toByteArray();
    }

    /**
     * manifest.json with a version that changes whenever the rest of the pack does: a Bedrock
     * client that has a pack with this uuid and version cached never downloads it again.
     */
    static byte[] versioned(SortedMap<String, byte[]> pack) {
        SortedMap<String, byte[]> content = new TreeMap<>(pack);
        content.remove("manifest.json");
        // Bedrock keeps each part of a version in 16 bits: 1..65535, never the jar's 0.
        int patch = 1 + Integer.parseInt(PackFiles.sha1(PackFiles.zip(content)).substring(0, 4), 16) % 65535;
        JsonObject manifest = ItemArt.parseObject(pack.get("manifest.json"));
        setPatch(manifest.getAsJsonObject("header"), patch);
        JsonElement modules = manifest.get("modules");
        if (modules != null && modules.isJsonArray()) {
            for (JsonElement module : modules.getAsJsonArray()) {
                if (module.isJsonObject()) {
                    setPatch(module.getAsJsonObject(), patch);
                }
            }
        }
        return ItemArt.json(manifest);
    }

    private static void setPatch(JsonObject object, int patch) {
        if (object == null || !object.has("version") || !object.get("version").isJsonArray()) {
            return;
        }
        JsonArray version = object.getAsJsonArray("version");
        if (version.size() == 3) {
            version.set(2, new JsonPrimitive(patch));
        }
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
