package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packs are data files a typo breaks silently in game (a purple-and-black cube, or Geyser
 * skipping a mapping), so their cross-references are checked here: every model, texture,
 * geometry and identifier one file names exists in another, and the custom model data strings
 * the plugin puts on its items are the ones the packs and mappings select on.
 */
class ResourcePackAssetsTest {

    static final Path ROOT = Path.of("src/main/resources/resourcepack");
    static final Path JAVA = ROOT.resolve("java");
    static final Path BEDROCK = ROOT.resolve("bedrock");

    /** BlockKind#modelData() of the Linked Workbench and the Combo Chest, by the item they are put on. */
    static final Map<String, String> MODEL_DATA = Map.of(
            "crafting_table", "craftbridge:linked_workbench",
            "barrel", "craftbridge:combo_chest");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Path file) throws IOException {
        // JSON is YAML; SnakeYAML ships with the Paper API, so no JSON library is needed here.
        return (Map<String, Object>) new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static int[] pngSize(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            byte[] signature = data.readNBytes(8);
            assertEquals(0x89, signature[0] & 0xFF, file + " is not a PNG");
            data.readInt();
            assertEquals("IHDR", new String(data.readNBytes(4), StandardCharsets.US_ASCII));
            return new int[] {data.readInt(), data.readInt()};
        }
    }

    // ---- Java ---------------------------------------------------------------------------

    @Test
    void packMcmetaCoversMinecraft262To263() throws IOException {
        Map<String, Object> pack = map(json(JAVA.resolve("pack.mcmeta")).get("pack"));
        assertEquals(List.of(88, 0), pack.get("min_format"), "26.2 is resource pack format 88.0");
        assertEquals(List.of(97, 1), pack.get("max_format"), "26.3 is resource pack format 97.1");
        assertTrue(pack.get("description") instanceof String);
    }

    @Test
    void theItemDefinitionsSelectOurStringsAndFallBackToTheVanillaBlock() throws IOException {
        for (Map.Entry<String, String> item : MODEL_DATA.entrySet()) {
            Map<String, Object> model = map(json(JAVA.resolve("assets/minecraft/items/" + item.getKey() + ".json")).get("model"));
            assertEquals("minecraft:select", model.get("type"));
            assertEquals("minecraft:custom_model_data", model.get("property"));
            assertEquals(0, model.get("index"));
            List<Object> cases = list(model.get("cases"));
            assertEquals(1, cases.size());
            Map<String, Object> only = map(cases.get(0));
            assertEquals(item.getValue(), only.get("when"));
            String custom = (String) map(only.get("model")).get("model");
            assertEquals(item.getValue().replace("craftbridge:", "craftbridge:block/"), custom);
            assertTrue(Files.isRegularFile(javaAsset(custom, "models", ".json")), custom);
            Map<String, Object> fallback = map(model.get("fallback"));
            assertEquals("minecraft:model", fallback.get("type"));
            assertEquals("minecraft:block/" + item.getKey(), fallback.get("model"), "the vanilla look without our data");
        }
    }

    @Test
    void everyTextureTheModelsUseExistsAndIsSquare() throws IOException {
        for (String name : List.of("linked_workbench", "combo_chest")) {
            Map<String, Object> model = json(JAVA.resolve("assets/craftbridge/models/block/" + name + ".json"));
            assertEquals("minecraft:block/block", model.get("parent"), "block display transforms for hands and GUI");
            Map<String, Object> textures = map(model.get("textures"));
            for (Object element : list(model.get("elements"))) {
                for (Object face : map(map(element).get("faces")).values()) {
                    String ref = (String) map(face).get("texture");
                    assertTrue(ref.startsWith("#") && textures.containsKey(ref.substring(1)), name + ": " + ref);
                }
            }
            for (Object texture : textures.values()) {
                Path png = javaAsset((String) texture, "textures", ".png");
                assertTrue(Files.isRegularFile(png), png.toString());
                int[] size = pngSize(png);
                assertEquals(size[0], size[1], png + " is square");
                assertTrue(size[0] == 16 || size[0] == 32, png + " is 16 or 32 px");
            }
        }
    }

    private static Path javaAsset(String id, String folder, String extension) {
        String[] parts = id.split(":", 2);
        return JAVA.resolve("assets").resolve(parts[0]).resolve(folder).resolve(parts[1] + extension);
    }

    // ---- Bedrock and Geyser -------------------------------------------------------------

    @Test
    void theManifestHasTwoDistinctFixedUuids() throws IOException {
        Map<String, Object> manifest = json(BEDROCK.resolve("manifest.json"));
        assertEquals(2, manifest.get("format_version"));
        String header = (String) map(manifest.get("header")).get("uuid");
        String module = (String) map(list(manifest.get("modules")).get(0)).get("uuid");
        UUID.fromString(header);
        UUID.fromString(module);
        assertTrue(!header.equals(module) && !header.equals(PackFiles.JAVA_PACK_ID.toString()));
        assertEquals("resources", map(list(manifest.get("modules")).get(0)).get("type"));
    }

    @Test
    void theGeyserMappingMatchesOurItemsAndThePack() throws IOException {
        Map<String, Object> mapping = json(ROOT.resolve("geyser").resolve(GeyserExport.MAPPINGS));
        assertEquals(2, mapping.get("format_version"));
        Map<String, Object> items = map(mapping.get("items"));
        assertEquals(Set.of("minecraft:crafting_table", "minecraft:barrel"), items.keySet());
        Map<String, Object> iconAtlas = map(json(BEDROCK.resolve("textures/item_texture.json")).get("texture_data"));
        Set<String> identifiers = new HashSet<>();
        for (Map.Entry<String, String> item : MODEL_DATA.entrySet()) {
            List<Object> definitions = list(items.get("minecraft:" + item.getKey()));
            assertEquals(1, definitions.size());
            Map<String, Object> definition = map(definitions.get(0));
            assertEquals("definition", definition.get("type"));
            assertEquals("minecraft:" + item.getKey(), definition.get("model"), "our items keep the vanilla item model");
            Map<String, Object> predicate = map(definition.get("predicate"));
            assertEquals("match", predicate.get("type"));
            assertEquals("custom_model_data", predicate.get("property"));
            assertEquals(item.getValue(), predicate.get("value"));
            assertEquals(0, predicate.get("index"));
            String bedrockId = (String) definition.get("bedrock_identifier");
            assertTrue(identifiers.add(bedrockId));
            assertTrue(!bedrockId.startsWith("minecraft:") && bedrockId.contains(":"), bedrockId);

            String icon = (String) map(definition.get("bedrock_options")).get("icon");
            assertTrue(iconAtlas.containsKey(icon), icon);
            for (Object path : list(map(iconAtlas.get(icon)).get("textures"))) {
                assertTrue(Files.isRegularFile(BEDROCK.resolve(path + ".png")), path.toString());
            }
            attachableFor(bedrockId);
        }
    }

    /** The attachable that draws a mapped item on Bedrock, its geometry, texture and animations. */
    private static void attachableFor(String bedrockId) throws IOException {
        Path file = BEDROCK.resolve("attachables/" + bedrockId.replace(':', '_') + ".json");
        Map<String, Object> description = map(map(json(file).get("minecraft:attachable")).get("description"));
        assertEquals(bedrockId, description.get("identifier"));
        String texture = (String) map(description.get("textures")).get("default");
        assertTrue(Files.isRegularFile(BEDROCK.resolve(texture + ".png")), texture);
        String geometry = (String) map(description.get("geometry")).get("default");
        List<String> geometries = new ArrayList<>();
        try (var files = Files.list(BEDROCK.resolve("models/entity"))) {
            for (Path geo : files.toList()) {
                for (Object g : list(json(geo).get("minecraft:geometry"))) {
                    Map<String, Object> d = map(map(g).get("description"));
                    geometries.add((String) d.get("identifier"));
                    if (d.get("identifier").equals(geometry)) {
                        int[] size = pngSize(BEDROCK.resolve(texture + ".png"));
                        assertEquals(d.get("texture_width"), size[0], texture);
                        assertEquals(d.get("texture_height"), size[1], texture);
                    }
                }
            }
        }
        assertTrue(geometries.contains(geometry), geometry + " in " + geometries);
        Map<String, Object> animations = map(json(BEDROCK.resolve("animations/craftbridge.animation.json")).get("animations"));
        for (Object animation : map(description.get("animations")).values()) {
            assertTrue(animations.containsKey(animation), animation.toString());
        }
    }

    @Test
    void theDisplayEntityMappingNamesTheSameItems() throws IOException {
        Map<String, Object> mappings = map(json(ROOT.resolve("geyser").resolve(GeyserExport.DISPLAY_MAPPINGS)).get("mappings"));
        Set<String> seen = new HashSet<>();
        for (Object entry : mappings.values()) {
            Map<String, Object> mapping = map(entry);
            String type = ((String) mapping.get("type")).replace("minecraft:", "");
            assertEquals(MODEL_DATA.get(type), mapping.get("item-identifier"), type);
            assertTrue(map(mapping.get("displayentityoptions")).containsKey("hand"));
            seen.add(type);
        }
        assertEquals(MODEL_DATA.keySet(), seen);
    }
}
