package com.dierks.craftbridge.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom item art replaces whole item definitions, so the one thing that must never go wrong
 * is the fallback: every item that is not a textured custom item has to look exactly as it
 * does without the pack. These tests check that against the bundled vanilla tables, and the
 * rest of what the art folder turns into.
 */
class ItemArtTest {

    static final Path JAVA_PACK = Path.of("src/main/resources/resourcepack/java");
    static final Path VANILLA = Path.of("src/main/resources/resourcepack/vanilla");

    static final byte[] PNG16 = png(16, 16);
    static final byte[] ANIMATION = "{\"animation\": {\"frametime\": 2}}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    static VanillaItems vanilla(String version) throws IOException {
        return VanillaItems.parse(Files.readAllBytes(VANILLA.resolve("items-" + version + ".json")));
    }

    static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            image.setRGB(x, x % height, 0xFF336699);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }

    static ItemArt.Result build(List<ItemArt.Item> items, Map<String, byte[]> folder, Set<String> overridden,
                                VanillaItems vanilla) throws IOException {
        return ItemArt.build(items, folder, PackFiles.fromDirectory(JAVA_PACK), overridden, vanilla,
                VanillaItems.differing(List.of(vanilla("26.2"), vanilla("26.3"))),
                vanilla == null ? "99.9" : vanilla.version());
    }

    static ItemArt.Item item(String id, String base) {
        return new ItemArt.Item(id, base, false);
    }

    static JsonObject parse(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static JsonObject file(ItemArt.Result result, String path) {
        byte[] bytes = result.files().get(path);
        assertNotNull(bytes, path + " in " + result.files().keySet());
        return parse(bytes);
    }

    // ---- the fallback ------------------------------------------------------------------

    @Test
    void theFallbackIsTheVanillaDefinitionAndItsOtherKeysStay() throws IOException {
        VanillaItems vanilla = vanilla("26.2");
        ItemArt.Result result = build(List.of(item("spear_of_note", "diamond_spear")),
                Map.of("spear_of_note.png", PNG16), Set.of(), vanilla);

        JsonObject definition = file(result, "assets/minecraft/items/diamond_spear.json");
        JsonObject original = vanilla.definition("diamond_spear");
        assertTrue(original.has("swap_animation_scale"), "the spear is the case with a second top-level key");
        assertEquals(List.copyOf(original.keySet()), List.copyOf(definition.keySet()), "same keys, same order");
        assertEquals(original.get("swap_animation_scale"), definition.get("swap_animation_scale"));
        JsonObject select = definition.getAsJsonObject("model");
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals("minecraft:custom_model_data", select.get("property").getAsString());
        assertEquals(0, select.get("index").getAsInt());
        assertEquals(original.get("model"), select.get("fallback"), "the fallback is vanilla's model, verbatim");
        JsonArray cases = select.getAsJsonArray("cases");
        assertEquals(1, cases.size());
        assertEquals("craftbridge:item/spear_of_note", cases.get(0).getAsJsonObject().get("when").getAsString());
        assertEquals("craftbridge:item/spear_of_note",
                cases.get(0).getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
    }

    /** Every item Minecraft has, in both versions: the generated definition falls back to vanilla's exactly. */
    @Test
    void everyVanillaItemFallsBackToItsOwnDefinition() throws IOException {
        for (String version : List.of("26.2", "26.3")) {
            VanillaItems vanilla = vanilla(version);
            JsonObject table = parse(Files.readAllBytes(VANILLA.resolve("items-" + version + ".json")));
            List<ItemArt.Item> items = new ArrayList<>();
            Map<String, byte[]> folder = new TreeMap<>();
            Set<String> differing = VanillaItems.differing(List.of(vanilla("26.2"), vanilla("26.3")));
            for (String base : table.getAsJsonObject("items").keySet()) {
                items.add(item("x_" + base, base));
                folder.put("x_" + base + ".png", PNG16);
            }
            ItemArt.Result result = build(items, folder, Set.of(), vanilla);

            assertEquals(differing.size(), result.skipped().size(), version + ": " + result.skipped());
            assertEquals(items.size() - differing.size(), result.textured().size(), version);
            for (String base : table.getAsJsonObject("items").keySet()) {
                if (differing.contains(base)) {
                    assertFalse(result.files().containsKey("assets/minecraft/items/" + base + ".json"), base);
                    continue;
                }
                JsonObject definition = file(result, "assets/minecraft/items/" + base + ".json");
                JsonObject original = vanilla.definition(base);
                assertEquals(List.copyOf(original.keySet()), List.copyOf(definition.keySet()), version + " " + base);
                JsonArray cases = definition.getAsJsonObject("model").getAsJsonArray("cases");
                assertEquals("craftbridge:item/x_" + base,
                        cases.get(cases.size() - 1).getAsJsonObject().get("when").getAsString());
                for (String key : original.keySet()) {
                    JsonElement expected = original.get(key);
                    JsonElement actual = key.equals("model") ? definition.getAsJsonObject("model").get("fallback")
                            : definition.get(key);
                    assertEquals(expected, actual, version + " " + base + " " + key);
                }
            }
        }
    }

    @Test
    void craftingTableAndBarrelKeepTheirBlockCasesAndGainTheItems() throws IOException {
        for (String version : List.of("26.2", "26.3")) {
            VanillaItems vanilla = vanilla(version);
            ItemArt.Result result = build(List.of(item("fancy_table", "crafting_table"), item("fancy_barrel", "barrel"),
                    item("another_table", "crafting_table")),
                    Map.of("fancy_table.png", PNG16, "fancy_barrel.png", PNG16, "another_table.png", PNG16),
                    Set.of(), vanilla);

            for (Map.Entry<String, List<String>> expected : Map.of(
                    "crafting_table", List.of("craftbridge:linked_workbench", "craftbridge:item/another_table",
                            "craftbridge:item/fancy_table"),
                    "barrel", List.of("craftbridge:combo_chest", "craftbridge:item/fancy_barrel")).entrySet()) {
                String base = expected.getKey();
                JsonObject select = file(result, "assets/minecraft/items/" + base + ".json").getAsJsonObject("model");
                List<String> whens = new ArrayList<>();
                for (JsonElement c : select.getAsJsonArray("cases")) {
                    whens.add(c.getAsJsonObject().get("when").getAsString());
                }
                assertEquals(expected.getValue(), whens, "the block's own case first, then the items by id");
                assertEquals(vanilla.definition(base).get("model"), select.get("fallback"),
                        version + ": CraftBridge's own " + base + ".json falls back to vanilla's");
            }
            assertTrue(result.textured().stream().allMatch(t -> t.definition().startsWith("shared with")));
        }
    }

    @Test
    void anOverrideForTheBaseWins() throws IOException {
        Path overrides = dir.resolve("overrides");
        Files.createDirectories(overrides.resolve("assets/minecraft/items"));
        Files.writeString(overrides.resolve("assets/minecraft/items/dried_kelp.json"), "{\"admin\": true}");
        SortedMap<String, byte[]> overrideFiles = PackFiles.fromDirectory(overrides);

        ItemArt.Result result = build(List.of(item("burned_zombie_flesh", "dried_kelp")),
                Map.of("burned_zombie_flesh.png", PNG16), overrideFiles.keySet(), vanilla("26.2"));

        assertFalse(result.files().containsKey("assets/minecraft/items/dried_kelp.json"), "not generated");
        assertTrue(result.files().containsKey("assets/craftbridge/models/item/burned_zombie_flesh.json"));
        assertTrue(result.files().containsKey("assets/craftbridge/textures/item/burned_zombie_flesh.png"));
        assertEquals("your override", result.textured().get(0).definition());
        assertTrue(result.warnings().get(0).contains("overrides/java/assets/minecraft/items/dried_kelp.json"),
                result.warnings().toString());
        SortedMap<String, byte[]> pack = PackFiles.overlay(
                PackFiles.overlay(PackFiles.fromDirectory(JAVA_PACK), result.files()), overrideFiles);
        assertEquals("{\"admin\": true}",
                new String(pack.get("assets/minecraft/items/dried_kelp.json"), StandardCharsets.UTF_8));
    }

    // ---- the models --------------------------------------------------------------------

    @Test
    void blockItemsGetACubeAndFlatItemsTheirOwnTemplate() throws IOException {
        VanillaItems vanilla = vanilla("26.2");
        Map<String, String> bases = new TreeMap<>(Map.of(
                "cobble", "cobblestone",
                "blade", "diamond_sword",
                "flesh", "dried_kelp",
                "disc", "music_disc_13",
                "hammer", "mace",
                "leaf", "big_dripleaf",
                "cap", "leather_helmet",
                "arrow", "compass"));
        List<ItemArt.Item> items = new ArrayList<>();
        Map<String, byte[]> folder = new TreeMap<>();
        bases.forEach((id, base) -> {
            items.add(item(id, base));
            folder.put(id + ".png", PNG16);
        });
        ItemArt.Result result = build(items, folder, Set.of(), vanilla);

        JsonObject cube = file(result, "assets/craftbridge/models/item/cobble.json");
        assertEquals("minecraft:block/cube_all", cube.get("parent").getAsString());
        assertEquals("craftbridge:block/cobble", cube.getAsJsonObject("textures").get("all").getAsString());
        assertTrue(result.files().containsKey("assets/craftbridge/textures/block/cobble.png"), "blocks atlas");
        assertFalse(result.files().containsKey("assets/craftbridge/textures/item/cobble.png"));

        Map<String, String> parents = Map.of(
                "blade", "minecraft:item/handheld",
                "flesh", "minecraft:item/generated",
                "disc", "minecraft:item/template_music_disc",
                "hammer", "minecraft:item/handheld_mace",
                "leaf", "minecraft:item/generated", // its vanilla model's parent is a block model
                "cap", "minecraft:item/generated", // tinted, not a plain model
                "arrow", "minecraft:item/generated"); // a select
        parents.forEach((id, parent) -> {
            JsonObject model = file(result, "assets/craftbridge/models/item/" + id + ".json");
            assertEquals(parent, model.get("parent").getAsString(), id);
            assertEquals("craftbridge:item/" + id, model.getAsJsonObject("textures").get("layer0").getAsString(), id);
            assertTrue(result.files().containsKey("assets/craftbridge/textures/item/" + id + ".png"), id);
        });
        assertArrayEquals(PNG16, result.files().get("assets/craftbridge/textures/item/flesh.png"), "copied verbatim");
    }

    @Test
    void aCustomModelIsUsedVerbatimWithTheTexturesItNames() throws IOException {
        byte[] model = ("{\"parent\": \"minecraft:item/handheld\", \"textures\": {"
                + "\"layer0\": \"craftbridge:item/blade\", \"layer1\": \"craftbridge:item/hilt\","
                + " \"layer2\": \"item/glow\", \"particle\": \"#layer0\", \"layer3\": \"minecraft:item/stick\"}}")
                .getBytes(StandardCharsets.UTF_8);
        ItemArt.Result result = build(List.of(item("sword_of_note", "iron_sword")),
                Map.of("sword_of_note.json", model, "blade.png", png(32, 64), "blade.png.mcmeta", ANIMATION,
                        "glow.png", PNG16),
                Set.of(), vanilla("26.2"));

        assertArrayEquals(model, result.files().get("assets/craftbridge/models/item/sword_of_note.json"));
        assertTrue(result.files().containsKey("assets/craftbridge/textures/item/blade.png"));
        assertTrue(result.files().containsKey("assets/craftbridge/textures/item/blade.png.mcmeta"));
        assertFalse(result.files().containsKey("assets/craftbridge/textures/item/sword_of_note.png"));
        ItemArt.Textured textured = result.textured().get(0);
        assertTrue(textured.customModel());
        assertEquals("no texture of its own", textured.size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no hilt.png")), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("craftbridge:item/glow")), result.warnings().toString());
        assertTrue(result.skipped().stream().anyMatch(s -> s.name().equals("glow.png")),
                "glow.png is not referenced as craftbridge:item/glow, so it is unused: " + result.skipped());
    }

    @Test
    void twoItemsOnOneBaseShareOneDefinitionInIdOrder() throws IOException {
        ItemArt.Result result = build(List.of(item("zeta", "paper"), item("alpha", "paper")),
                Map.of("zeta.png", PNG16, "alpha.png", PNG16), Set.of(), vanilla("26.3"));

        JsonArray cases = file(result, "assets/minecraft/items/paper.json").getAsJsonObject("model").getAsJsonArray("cases");
        assertEquals(2, cases.size());
        assertEquals("craftbridge:item/alpha", cases.get(0).getAsJsonObject().get("when").getAsString());
        assertEquals("craftbridge:item/zeta", cases.get(1).getAsJsonObject().get("when").getAsString());
    }

    // ---- what is left out --------------------------------------------------------------

    @Test
    void playerHeadsKeepTheirSkin() throws IOException {
        ItemArt.Result result = build(List.of(new ItemArt.Item("founders_skull", "player_head", true)),
                Map.of("founders_skull.png", PNG16), Set.of(), vanilla("26.2"));

        assertTrue(result.files().isEmpty(), result.files().keySet().toString());
        assertEquals(1, result.skipped().size());
        assertEquals("founders_skull", result.skipped().get(0).name());
        assertTrue(result.skipped().get(0).reason().contains("player heads keep their skin"), result.skipped().toString());
    }

    @Test
    void filesNoItemUsesAreReportedByName() throws IOException {
        ItemArt.Result result = build(List.of(item("burned_zombie_flesh", "dried_kelp")),
                Map.of("burnt_zombie_flesh.png", PNG16, "lonely.png.mcmeta", ANIMATION, "notes.jpg", new byte[] {1},
                        "README.txt", new byte[] {1}, "sub/deep.png", PNG16),
                Set.of(), vanilla("26.2"));

        assertTrue(result.files().isEmpty());
        assertEquals(List.of(), result.textured());
        Map<String, String> reasons = new TreeMap<>();
        result.skipped().forEach(s -> reasons.put(s.name(), s.reason()));
        assertEquals(Set.of("burnt_zombie_flesh.png", "lonely.png.mcmeta", "notes.jpg", "sub/deep.png"), reasons.keySet());
        assertEquals("no custom item has the id burnt_zombie_flesh", reasons.get("burnt_zombie_flesh.png"));
        assertTrue(reasons.get("lonely.png.mcmeta").contains("no lonely.png"));
    }

    @Test
    void aTextureWithAProblemKeepsTheItemPlain() throws IOException {
        ItemArt.Result result = build(List.of(item("big", "paper"), item("strip", "paper")),
                Map.of("big.png", png(256, 256), "strip.png", png(16, 48)), Set.of(), vanilla("26.2"));

        assertTrue(result.files().isEmpty());
        assertEquals(2, result.skipped().size(), result.skipped().toString());
        assertTrue(result.skipped().get(0).reason().contains("16, 32, 64 or 128"), result.skipped().toString());
        assertTrue(result.skipped().get(1).reason().contains("add strip.png.mcmeta"), result.skipped().toString());
    }

    @Test
    void withoutAVanillaTableNoItemGetsArt() throws IOException {
        ItemArt.Result result = build(List.of(item("burned_zombie_flesh", "dried_kelp")),
                Map.of("burned_zombie_flesh.png", PNG16), Set.of(), null);

        assertTrue(result.files().isEmpty());
        assertTrue(result.skipped().get(0).reason().contains("no vanilla item table for Minecraft 99.9"),
                result.skipped().toString());
    }

    @Test
    void anIdThatWouldOverwriteOurOwnTextureIsRefused() throws IOException {
        ItemArt.Result result = build(List.of(item("linked_workbench_top", "cobblestone")),
                Map.of("linked_workbench_top.png", PNG16), Set.of(), vanilla("26.2"));

        assertTrue(result.files().isEmpty());
        assertTrue(result.skipped().get(0).reason().contains("textures/block/linked_workbench_top.png"),
                result.skipped().toString());
    }

    @Test
    void windowsFileNamesMatchWhateverTheirCase() throws IOException {
        ItemArt.Result result = build(List.of(item("burned_zombie_flesh", "dried_kelp")),
                Map.of("Burned_Zombie_Flesh.PNG", PNG16), Set.of(), vanilla("26.2"));

        assertEquals(1, result.textured().size(), result.skipped().toString());
        assertTrue(result.files().containsKey("assets/craftbridge/textures/item/burned_zombie_flesh.png"));
    }

    @Test
    void noArtMeansNoFilesSoThePackIsUnchanged() throws IOException {
        ItemArt.Result result = build(List.of(item("burned_zombie_flesh", "dried_kelp")), Map.of(), Set.of(),
                vanilla("26.2"));

        assertTrue(result.files().isEmpty());
        assertEquals("0 custom items have art, 0 skipped", result.summary());
    }

    @Test
    void theSameFolderAlwaysGivesTheSameBytes() throws IOException {
        List<ItemArt.Item> items = List.of(item("b", "paper"), item("a", "cobblestone"), item("c", "crafting_table"));
        Map<String, byte[]> folder = Map.of("a.png", PNG16, "b.png", png(16, 32), "b.png.mcmeta", ANIMATION, "c.png", PNG16);

        byte[] first = PackFiles.zip(build(items, folder, Set.of(), vanilla("26.2")).files());
        byte[] second = PackFiles.zip(build(items, new TreeMap<>(folder), Set.of(), vanilla("26.2")).files());

        assertArrayEquals(first, second);
    }

    @Test
    void theSummaryNamesWhatHasArtAndWhatWasLeftOut() throws IOException {
        ItemArt.Result result = build(List.of(item("flesh", "dried_kelp"), new ItemArt.Item("skull", "player_head", true)),
                Map.of("flesh.png", PNG16, "skull.png", PNG16), Set.of(), vanilla("26.2"));

        assertTrue(result.summary().startsWith("1 custom item has art (flesh), 1 skipped (skull: player heads"),
                result.summary());
    }

    @Test
    void anItemTheTwoVersionsDrawDifferentlyGetsNoArt() throws IOException {
        assertEquals(Set.of("filled_map", "light"), VanillaItems.differing(List.of(vanilla("26.2"), vanilla("26.3"))),
                "the only definitions 26.2 and 26.3 disagree on");
        for (String version : List.of("26.2", "26.3")) {
            ItemArt.Result result = build(List.of(item("treasure_map", "filled_map")),
                    Map.of("treasure_map.png", PNG16), Set.of(), vanilla(version));

            assertTrue(result.files().isEmpty(), "the pack serves 26.2 and 26.3 clients alike, so neither fallback fits both");
            assertTrue(result.skipped().get(0).reason().contains("draw filled_map differently"), result.skipped().toString());
        }
    }

    @Test
    void modelsAndAnimationsAreCheckedAsStrictlyAsTheClientReadsThem() throws IOException {
        byte[] commented = "{\"parent\": \"minecraft:item/handheld\", // from Blockbench\n \"textures\": {}}"
                .getBytes(StandardCharsets.UTF_8);
        ItemArt.Result result = build(List.of(item("sword", "iron_sword"), item("wave", "paper"), item("tick", "paper")),
                Map.of("sword.json", commented,
                        "wave.png", png(16, 32), "wave.png.mcmeta", "{animation: {frametime: 2}}".getBytes(StandardCharsets.UTF_8),
                        "tick.png", png(16, 32), "tick.png.mcmeta", "{\"animation\": {\"frametime\": 0}}".getBytes(StandardCharsets.UTF_8)),
                Set.of(), vanilla("26.2"));

        assertTrue(result.files().isEmpty(), result.files().keySet().toString());
        Map<String, String> reasons = new TreeMap<>();
        result.skipped().forEach(s -> reasons.put(s.name(), s.reason()));
        assertTrue(reasons.get("sword").contains("not a JSON model"), reasons.toString());
        assertTrue(reasons.get("wave").contains("not valid JSON"), reasons.toString());
        assertTrue(reasons.get("tick").contains("whole number of at least 1"), reasons.toString());
        assertTrue(ItemArt.pngProblem("a.png", png(16, 64), "{\"animation\": {\"height\": 24}}".getBytes(StandardCharsets.UTF_8))
                .contains("does not divide"));
        assertNull(ItemArt.pngProblem("a.png", png(16, 64), "{\"animation\": {\"frametime\": 3, \"height\": 16}}"
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCustomModelMayNameItsTexturesInEveryFormTheClientAccepts() throws IOException {
        byte[] model = ("{\"parent\": \"minecraft:block/cube_all\", \"textures\": {"
                + "\"all\": {\"sprite\": \"craftbridge:item/glass_blade\", \"force_translucent\": true},"
                + " \"particle\": \"craftbridge:block/marble\", \"side\": \"craftbridge:item/cracked\"}}")
                .getBytes(StandardCharsets.UTF_8);
        ItemArt.Result result = build(List.of(item("fancy", "stone")),
                Map.of("fancy.json", model, "glass_blade.png", PNG16, "marble.png", PNG16, "cracked.png", png(17, 17)),
                Set.of(), vanilla("26.2"));

        assertTrue(result.files().containsKey("assets/craftbridge/textures/item/glass_blade.png"), "the object form");
        assertTrue(result.files().containsKey("assets/craftbridge/textures/block/marble.png"), "a block texture");
        assertFalse(result.files().containsKey("assets/craftbridge/textures/item/cracked.png"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cracked.png is 17x17")), result.warnings().toString());
        assertEquals(List.of(), result.skipped(), "a named texture that is left out is not also reported as an unused file");
    }

    // ---- textures ----------------------------------------------------------------------

    @Test
    void texturesMustBeSquareOrAnimatedStrips() {
        for (int size : ItemArt.SIZES) {
            assertNull(ItemArt.pngProblem("a.png", png(size, size), null), size + "px");
        }
        assertNull(ItemArt.pngProblem("a.png", png(16, 16), ANIMATION), "a one-frame animation is fine");
        assertNull(ItemArt.pngProblem("a.png", png(32, 128), ANIMATION));
        assertTrue(ItemArt.pngProblem("a.png", png(16, 64), null).contains("add a.png.mcmeta"));
        assertTrue(ItemArt.pngProblem("a.png", png(17, 17), null).contains("16, 32, 64 or 128"));
        assertTrue(ItemArt.pngProblem("a.png", png(256, 256), null).contains("16, 32, 64 or 128"));
        assertTrue(ItemArt.pngProblem("a.png", png(16, 24), ANIMATION).contains("multiple of width"));
        assertTrue(ItemArt.pngProblem("a.png", png(32, 16), ANIMATION).contains("multiple of width"));
        assertTrue(ItemArt.pngProblem("a.png", png(16, 32), "{\"frametime\": 2}".getBytes(StandardCharsets.UTF_8))
                .contains("no \"animation\" section"));
        assertTrue(ItemArt.pngProblem("a.png", png(16, 32), "{oops".getBytes(StandardCharsets.UTF_8))
                .contains("not valid JSON"));
        assertTrue(ItemArt.pngProblem("a.png", png(16, 16 * 257), ANIMATION).contains("256 frames at most"));
    }

    @Test
    void anOversizedImageIsRefusedByTheSizeInItsHeader() {
        // The signature and IHDR only, no image data: this passes only if nothing is decoded.
        byte[] huge = Arrays.copyOf(png(4096, 4096), 33);
        assertEquals(new ItemArt.Png(4096, 4096), ItemArt.readPng(huge));
        assertEquals("huge.png is 4096x4096: make it 16, 32, 64 or 128 pixels wide", ItemArt.pngProblem("huge.png", huge, null));
    }

    @Test
    void theAnimationRulesFollowTheClient() {
        byte[] strip = png(16, 64);
        assertNull(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"frametime\": 1.5}}")), "read as 1");
        assertNull(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"interpolate\": true,"
                + " \"frames\": [0, 1, {\"index\": 2, \"time\": 5}, 3]}, \"texture\": {\"blur\": false}}")));
        assertNull(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {}} trailing")), "what follows the value is never read");
        assertTrue(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"interpolate\": \"true\"}}")).contains("true or false"));
        assertTrue(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"frames\": [{\"index\": 0, \"time\": 0}]}}"))
                .contains("neither a frame number"));
        assertTrue(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"frames\": 3}}")).contains("not a list"));
        assertTrue(ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {}, \"texture\": {\"clamp\": 1}}")).contains("true or false"));
        String bad = ItemArt.pngProblem("a.png", strip, meta("{\"animation\": {\"frametime\": 2,}}"));
        assertTrue(bad.contains("not strict JSON at line 1") && !bad.contains("setStrictness"), bad);
    }

    static byte[] meta(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aCustomModelCannotReplaceOurOwnBlockTextures() throws IOException {
        byte[] model = "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"craftbridge:block/linked_workbench_top\"}}"
                .getBytes(StandardCharsets.UTF_8);
        ItemArt.Result result = build(List.of(item("fancy_table", "stone")),
                Map.of("fancy_table.json", model, "linked_workbench_top.png", PNG16), Set.of(), vanilla("26.2"));

        assertFalse(result.files().containsKey("assets/craftbridge/textures/block/linked_workbench_top.png"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("would replace CraftBridge's own")), result.warnings().toString());
        assertEquals(List.of(), result.skipped());
    }

    @Test
    void onlyARealPngThatDecodesIsATexture() throws IOException {
        assertTrue(ItemArt.pngProblem("a.png", "not an image".getBytes(StandardCharsets.UTF_8), null).contains("not a PNG"));
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", jpeg);
        assertTrue(ItemArt.pngProblem("a.png", jpeg.toByteArray(), null).contains("not a PNG"), "a renamed JPEG");
        byte[] truncated = Arrays.copyOf(PNG16, PNG16.length / 2);
        assertTrue(ItemArt.pngProblem("a.png", truncated, null).contains("cannot be read"));
    }

    // ---- the editor's line -------------------------------------------------------------

    @Test
    void theEditorSeesTheSizeOrTheProblem() throws IOException {
        Files.write(dir.resolve("flesh.png"), PNG16);
        Files.write(dir.resolve("Strip.png"), png(16, 64));
        Files.write(dir.resolve("strip.png.mcmeta"), ANIMATION);
        Files.write(dir.resolve("huge.png"), png(256, 256));

        assertEquals(new ItemArt.Status(true, "found (16x16)"), ItemArt.status(dir, "flesh"));
        assertEquals(new ItemArt.Status(true, "found (16x64, animated)"), ItemArt.status(dir, "strip"));
        assertFalse(ItemArt.status(dir, "huge").usable());
        assertNull(ItemArt.status(dir, "nothing"));
        assertNull(ItemArt.status(dir.resolve("missing"), "flesh"));
    }
}
