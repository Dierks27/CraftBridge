package com.dierks.craftbridge.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserExportTest {

    static final Path BEDROCK_PACK = Path.of("src/main/resources/resourcepack/bedrock");
    static final Path GEYSER = Path.of("src/main/resources/resourcepack/geyser");

    @TempDir
    Path plugins;

    private List<String> export() throws IOException {
        return GeyserExport.export(PackFiles.fromDirectory(BEDROCK_PACK),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.MAPPINGS)),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.DISPLAY_MAPPINGS)),
                plugins.resolve("CraftBridge/geyser"), plugins);
    }

    @Test
    void withoutGeyserHereTheFilesLandInOurFolderWithInstructions() throws IOException {
        List<String> lines = export();

        Path out = plugins.resolve("CraftBridge/geyser");
        assertTrue(Files.isRegularFile(out.resolve(GeyserExport.MCPACK)));
        assertTrue(Files.isRegularFile(out.resolve(GeyserExport.MAPPINGS)));
        assertTrue(Files.isRegularFile(out.resolve(GeyserExport.DISPLAY_MAPPINGS)));
        assertFalse(Files.exists(plugins.resolve("Geyser-Spigot")), "never creates Geyser's folder");
        assertTrue(lines.get(lines.size() - 2).contains("copy"), lines.toString());
    }

    @Test
    void theMcpackIsTheBedrockPackWithTheManifestAtTheRoot() throws IOException {
        export();
        SortedMap<String, byte[]> files = PackFiles.fromDirectory(BEDROCK_PACK);
        List<String> names = new ArrayList<>();
        byte[] mcpack = Files.readAllBytes(plugins.resolve("CraftBridge/geyser").resolve(GeyserExport.MCPACK));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(mcpack))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
                assertArrayEquals(files.get(e.getName()), zip.readAllBytes());
            }
        }
        assertEquals(new ArrayList<>(files.keySet()), names);
        assertTrue(names.contains("manifest.json"));
    }

    @Test
    void withGeyserAndTheDisplayExtensionEverythingIsCopiedIn() throws IOException {
        Path geyser = plugins.resolve("Geyser-Spigot");
        Files.createDirectories(geyser.resolve("extensions/geyserdisplayentity"));

        List<String> lines = export();

        assertTrue(Files.isRegularFile(geyser.resolve("packs").resolve(GeyserExport.MCPACK)));
        assertTrue(Files.isRegularFile(geyser.resolve("custom_mappings").resolve(GeyserExport.MAPPINGS)));
        Path display = geyser.resolve("extensions/geyserdisplayentity/Mappings/craftbridge.yml");
        assertTrue(Files.readString(display, StandardCharsets.UTF_8).contains("craftbridge:linked_workbench"));
        assertTrue(lines.get(lines.size() - 1).startsWith("Restart"), lines.toString());
    }

    @Test
    void withGeyserButNoDisplayExtensionItSaysWhatIsMissing() throws IOException {
        Files.createDirectories(plugins.resolve("Geyser-Spigot"));

        List<String> lines = export();

        assertTrue(lines.stream().anyMatch(l -> l.contains("GeyserDisplayEntity is not installed")), lines.toString());
        assertFalse(Files.exists(plugins.resolve("Geyser-Spigot/extensions")));
    }

    @Test
    void exportingTwiceReplacesTheFiles() throws IOException {
        Files.createDirectories(plugins.resolve("Geyser-Spigot"));
        export();
        export();
        try (var files = Files.list(plugins.resolve("Geyser-Spigot/packs"))) {
            assertEquals(List.of(GeyserExport.MCPACK), files.map(p -> p.getFileName().toString()).toList());
        }
    }
    // ---- custom item icons and bedrock.geyser-folder ------------------------------------

    private static final List<GeyserExport.BedrockItem> ITEMS = List.of(
            new GeyserExport.BedrockItem("burned_zombie_flesh", "dried_kelp", "Burned Zombie Flesh", ItemArtTest.png(16, 16)),
            new GeyserExport.BedrockItem("fancy_table", "crafting_table", "Fancy Table", ItemArtTest.png(32, 96)));

    private List<String> exportWithItems(Path geyserFolder) throws IOException {
        return GeyserExport.export(PackFiles.fromDirectory(BEDROCK_PACK),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.MAPPINGS)),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.DISPLAY_MAPPINGS)),
                ITEMS, plugins.resolve("CraftBridge/geyser"), plugins, geyserFolder);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new java.util.TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void customItemsWithArtGetAnIconAndAMappingOnTheirBaseItem() throws IOException {
        List<String> lines = exportWithItems(null);

        Path out = plugins.resolve("CraftBridge/geyser");
        JsonObject items = json(Files.readAllBytes(out.resolve(GeyserExport.MAPPINGS))).getAsJsonObject("items");
        JsonArray kelp = items.getAsJsonArray("minecraft:dried_kelp");
        assertEquals(1, kelp.size());
        JsonObject flesh = kelp.get(0).getAsJsonObject();
        assertEquals("definition", flesh.get("type").getAsString());
        assertEquals("minecraft:dried_kelp", flesh.get("model").getAsString());
        assertEquals("craftbridge:item_burned_zombie_flesh", flesh.get("bedrock_identifier").getAsString());
        assertEquals("Burned Zombie Flesh", flesh.get("display_name").getAsString());
        JsonObject predicate = flesh.getAsJsonObject("predicate");
        assertEquals("match", predicate.get("type").getAsString());
        assertEquals("custom_model_data", predicate.get("property").getAsString());
        assertEquals("craftbridge:item/burned_zombie_flesh", predicate.get("value").getAsString());
        assertEquals(0, predicate.get("index").getAsInt());
        JsonArray table = items.getAsJsonArray("minecraft:crafting_table");
        assertEquals(2, table.size(), "the Linked Workbench's definition stays, the item is added after it");
        assertEquals("craftbridge:linked_workbench", table.get(0).getAsJsonObject().getAsJsonObject("predicate").get("value").getAsString());

        Map<String, byte[]> pack = unzip(Files.readAllBytes(out.resolve(GeyserExport.MCPACK)));
        JsonObject atlas = json(pack.get("textures/item_texture.json")).getAsJsonObject("texture_data");
        assertTrue(atlas.has("craftbridge.linked_workbench"), "the blocks' icons stay");
        for (JsonElement definition : List.of(flesh, table.get(1))) {
            String icon = definition.getAsJsonObject().getAsJsonObject("bedrock_options").get("icon").getAsString();
            String path = atlas.getAsJsonObject(icon).getAsJsonArray("textures").get(0).getAsString();
            assertTrue(pack.containsKey(path + ".png"), path);
        }
        ItemArt.Png strip = ItemArt.readPng(pack.get("textures/items/craftbridge/items/fancy_table.png"));
        assertEquals(new ItemArt.Png(32, 32), strip, "an animation strip's first frame");

        JsonObject jarManifest = json(Files.readAllBytes(BEDROCK_PACK.resolve("manifest.json")));
        JsonObject manifest = json(pack.get("manifest.json"));
        assertNotEquals(jarManifest.getAsJsonObject("header").get("version"), manifest.getAsJsonObject("header").get("version"),
                "new content, new version: Bedrock clients keep a cached pack with the same one");
        assertEquals(jarManifest.getAsJsonObject("header").get("uuid"), manifest.getAsJsonObject("header").get("uuid"));
        assertEquals(manifest.getAsJsonObject("header").get("version"),
                manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("version"));
        for (JsonElement part : manifest.getAsJsonObject("header").getAsJsonArray("version")) {
            assertTrue(part.getAsInt() >= 0 && part.getAsInt() <= 65535, "Bedrock keeps version parts in 16 bits: " + part);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("burned_zombie_flesh, fancy_table")), lines.toString());
    }

    @Test
    void everyPathInTheBedrockPackStaysShortEnoughForConsoles() throws IOException {
        String longId = "a".repeat(64);
        GeyserExport.export(PackFiles.fromDirectory(BEDROCK_PACK),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.MAPPINGS)),
                Files.readAllBytes(GEYSER.resolve(GeyserExport.DISPLAY_MAPPINGS)),
                List.of(new GeyserExport.BedrockItem(longId, "paper", "Long", ItemArtTest.png(16, 16))),
                plugins.resolve("CraftBridge/geyser"), plugins, null);

        Map<String, byte[]> pack = unzip(Files.readAllBytes(plugins.resolve("CraftBridge/geyser").resolve(GeyserExport.MCPACK)));
        assertTrue(pack.keySet().stream().allMatch(p -> p.length() < 80), pack.keySet().toString());
        JsonObject atlas = json(pack.get("textures/item_texture.json")).getAsJsonObject("texture_data");
        String path = atlas.getAsJsonObject("craftbridge.item." + longId).getAsJsonArray("textures").get(0).getAsString();
        assertTrue(pack.containsKey(path + ".png"), path);
    }

    @Test
    void copyingByHandStillSaysToRestartGeyser() throws IOException {
        List<String> lines = export();
        assertTrue(lines.get(lines.size() - 1).contains("restart"), lines.toString());
    }

    @Test
    void theSameItemsGiveTheSamePack() throws IOException {
        exportWithItems(null);
        byte[] first = Files.readAllBytes(plugins.resolve("CraftBridge/geyser").resolve(GeyserExport.MCPACK));
        exportWithItems(null);
        assertArrayEquals(first, Files.readAllBytes(plugins.resolve("CraftBridge/geyser").resolve(GeyserExport.MCPACK)));
    }

    @Test
    void theGeyserFolderSettingCopiesIntoGeyserOnTheProxy() throws IOException {
        Path velocity = plugins.resolve("velocity/plugins/Geyser-Velocity");
        Files.createDirectories(velocity.resolve("extensions/geyserdisplayentity"));

        List<String> lines = exportWithItems(velocity);

        assertTrue(Files.isRegularFile(velocity.resolve("packs").resolve(GeyserExport.MCPACK)));
        assertTrue(Files.isRegularFile(velocity.resolve("custom_mappings").resolve(GeyserExport.MAPPINGS)));
        assertTrue(Files.isRegularFile(velocity.resolve("extensions/geyserdisplayentity/Mappings/craftbridge.yml")));
        assertTrue(lines.get(lines.size() - 1).startsWith("Restart the proxy"), lines.toString());
        assertFalse(Files.exists(plugins.resolve("Geyser-Spigot")));
    }

    @Test
    void aGeyserFolderThatIsNotThereIsReportedNotCreated() throws IOException {
        Path missing = plugins.resolve("nope/Geyser-Velocity");

        List<String> lines = exportWithItems(missing);

        assertFalse(Files.exists(missing));
        assertTrue(lines.get(lines.size() - 2).contains("not a folder"), lines.toString());
        assertTrue(Files.isRegularFile(plugins.resolve("CraftBridge/geyser").resolve(GeyserExport.MCPACK)));
    }
}
