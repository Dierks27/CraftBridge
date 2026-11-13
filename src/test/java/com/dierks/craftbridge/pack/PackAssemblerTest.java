package com.dierks.craftbridge.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How sources become one pack: priority, conflicts, the merged files, glyphs and the checks. */
class PackAssemblerTest {

    private static final PackAssembler.Settings SETTINGS = new PackAssembler.Settings(
            List.of("craftbridge", "hcm", "lilahcraft-clocks"), 97, 1, "test pack");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    /** Glyph keys get characters in the order asked, from U+E000, like a fresh glyphs.yml. */
    private final Map<String, Integer> assigned = new HashMap<>();

    private int charFor(String key) {
        return assigned.computeIfAbsent(key, k -> Glyphs.FIRST + assigned.size());
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static PackAssembler.Source source(String id, String... pathsAndContents) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            String content = pathsAndContents[i + 1];
            files.put(pathsAndContents[i], content.equals("PNG") ? PNG : b(content));
        }
        return new PackAssembler.Source(id, "test " + id, files);
    }

    private PackAssembler.Result assemble(PackAssembler.Source... sources) {
        return PackAssembler.assemble(List.of(sources), SETTINGS, this::charFor);
    }

    private static JsonObject json(PackAssembler.Result result, String path) {
        return JsonParser.parseString(new String(result.files().get(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String cmdSelect(String when, String model, String fallback) {
        return "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\",\"index\":0,"
                + "\"cases\":[{\"when\":\"" + when + "\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model
                + "\"}}],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"" + fallback + "\"}}}";
    }

    @Test
    void priorityDecidesTheOrderNeverTheOrderGiven() {
        List<PackAssembler.Source> ordered = PackAssembler.order(List.of(source("zeta"), source("lilahcraft-clocks"),
                source("alpha"), source("craftbridge")), SETTINGS.priority());
        assertEquals(List.of("craftbridge", "lilahcraft-clocks", "alpha", "zeta"),
                ordered.stream().map(PackAssembler.Source::id).toList());
    }

    @Test
    void theSameFileInTwoSourcesIsAConflictTheHigherOneWins() {
        PackAssembler.Result result = assemble(
                source("lilahcraft-clocks", "assets/minecraft/textures/item/clock_00.png", "clocks"),
                source("hcm", "assets/minecraft/textures/item/clock_00.png", "hcm"));
        assertArrayEquals(b("hcm"), result.files().get("assets/minecraft/textures/item/clock_00.png"));
        assertEquals(1, result.errors().size());
        String error = result.errors().get(0);
        assertTrue(error.contains("'hcm'") && error.contains("'lilahcraft-clocks'"), error);
    }

    @Test
    void theSameBytesInTwoSourcesIsNotAConflict() {
        PackAssembler.Result result = assemble(source("hcm", "assets/x/textures/a.png", "same"),
                source("collins", "assets/x/textures/a.png", "same"));
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    @Test
    void theClockMergesUnderCraftBridgesCustomItemCases() {
        String clock = "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:time\","
                + "\"entries\":[],\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/clock_00\"}}}";
        PackAssembler.Result result = assemble(
                source("craftbridge", "assets/minecraft/items/clock.json",
                        cmdSelect("craftbridge:item/pocket_watch", "craftbridge:item/pocket_watch", "minecraft:item/clock_00"),
                        "assets/craftbridge/models/item/pocket_watch.json", "{\"parent\":\"minecraft:item/generated\"}"),
                source("lilahcraft-clocks", "assets/minecraft/items/clock.json", clock));
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        JsonObject model = json(result, "assets/minecraft/items/clock.json").getAsJsonObject("model");
        assertEquals("minecraft:select", model.get("type").getAsString());
        assertEquals("craftbridge:item/pocket_watch", model.getAsJsonArray("cases").get(0).getAsJsonObject()
                .get("when").getAsString());
        assertEquals("minecraft:range_dispatch", model.getAsJsonObject("fallback").get("type").getAsString(),
                "every other clock draws the clock pack's way");
        assertEquals(List.of("items/clock.json"), result.vanillaOverrides().get("craftbridge+lilahcraft-clocks"));
    }

    @Test
    void customModelDataCasesFromTwoSourcesAreBothKept() {
        PackAssembler.Result result = assemble(
                source("craftbridge", "assets/minecraft/items/paper.json", cmdSelect("craftbridge:item/a", "craftbridge:item/a", "minecraft:item/paper"),
                        "assets/craftbridge/models/item/a.json", "{}"),
                source("hcm", "assets/minecraft/items/paper.json", cmdSelect("hcm:card", "hcm:item/card", "minecraft:item/paper"),
                        "assets/hcm/models/item/card.json", "{}"));
        JsonArray cases = json(result, "assets/minecraft/items/paper.json").getAsJsonObject("model").getAsJsonArray("cases");
        assertEquals(2, cases.size());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    @Test
    void twoWholeItemDefinitionsAreAConflict() {
        String a = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/a\"}}";
        String c = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/c\"}}";
        PackAssembler.Result result = assemble(source("hcm", "assets/minecraft/items/clock.json", a),
                source("lilahcraft-clocks", "assets/minecraft/items/clock.json", c));
        assertEquals(1, result.errors().size());
        assertArrayEquals(b(a), result.files().get("assets/minecraft/items/clock.json"));
    }

    @Test
    void fontsAtlasesSoundsAndLangMerge() {
        PackAssembler.Result result = assemble(
                source("hcm",
                        "assets/minecraft/atlases/blocks.json", "{\"sources\":[{\"type\":\"directory\",\"source\":\"hcm\",\"prefix\":\"hcm/\"}]}",
                        "assets/minecraft/sounds.json", "{\"hcm.coin\":{\"sounds\":[\"hcm:coin\"]},\"shared\":{\"sounds\":[\"hcm:a\"]}}",
                        "assets/minecraft/lang/en_us.json", "{\"hcm.title\":\"Market\",\"shared.key\":\"from hcm\"}",
                        "assets/hcm/font/menu.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\" \":4}}]}"),
                source("collins",
                        "assets/minecraft/atlases/blocks.json", "{\"sources\":[{\"type\":\"directory\",\"source\":\"collins\",\"prefix\":\"collins/\"}]}",
                        "assets/minecraft/sounds.json", "{\"collins.tv\":{\"sounds\":[\"collins:tv\"]},\"shared\":{\"sounds\":[\"collins:b\"]}}",
                        "assets/minecraft/lang/en_us.json", "{\"collins.tv\":\"TV\",\"shared.key\":\"from collins\"}",
                        "assets/hcm/font/menu.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\"\\t\":8}}]}"));
        assertEquals(2, json(result, "assets/minecraft/atlases/blocks.json").getAsJsonArray("sources").size());
        JsonObject sounds = json(result, "assets/minecraft/sounds.json");
        assertTrue(sounds.has("hcm.coin") && sounds.has("collins.tv"));
        assertEquals("hcm:a", sounds.getAsJsonObject("shared").getAsJsonArray("sounds").get(0).getAsString(),
                "hcm is in the priority list and collins is not, so hcm's wins");
        JsonObject lang = json(result, "assets/minecraft/lang/en_us.json");
        assertEquals(3, lang.size());
        assertEquals("from hcm", lang.get("shared.key").getAsString());
        assertEquals(2, json(result, "assets/hcm/font/menu.json").getAsJsonArray("providers").size());
        assertTrue(result.errors().isEmpty(), "merged files are not conflicts");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("en_us.json") && w.contains("shared.key")),
                result.warnings().toString());
        assertTrue(result.vanillaOverrides().isEmpty(), "merged files do not replace vanilla ones");
    }

    @Test
    void everyOtherVanillaFileIsListed() {
        PackAssembler.Result result = assemble(source("lilahcraft-clocks",
                "assets/minecraft/textures/item/clock_00.png", "PNG",
                "assets/minecraft/models/item/clock_00.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/clock_00\"}}"));
        assertEquals(List.of("models/item/clock_00.json", "textures/item/clock_00.png"),
                result.vanillaOverrides().get("lilahcraft-clocks"));
    }

    @Test
    void aBrokenJsonFileIsLeftOutAndTheRestIsBuilt() {
        PackAssembler.Result result = assemble(source("hcm",
                "assets/hcm/models/item/broken.json", "{\"parent\": ",
                "assets/hcm/models/item/fine.json", "{\"parent\":\"minecraft:item/generated\"}"));
        assertFalse(result.files().containsKey("assets/hcm/models/item/broken.json"));
        assertTrue(result.files().containsKey("assets/hcm/models/item/fine.json"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("hcm: assets/hcm/models/item/broken.json")),
                result.warnings().toString());
    }

    @Test
    void aLenientJsonFileIsKeptWithAWarning() {
        PackAssembler.Result result = assemble(source("hcm", "assets/hcm/models/item/x.json", "{/* from Blockbench */ parent: 'minecraft:item/generated'}"));
        assertTrue(result.files().containsKey("assets/hcm/models/item/x.json"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not strict JSON")), result.warnings().toString());
    }

    @Test
    void missingTexturesParentsAndModelsAreWarnedAbout() {
        PackAssembler.Result result = assemble(source("hcm",
                "assets/hcm/models/item/coin.json", "{\"parent\":\"hcm:item/base\",\"textures\":{\"layer0\":\"hcm:item/coin\","
                        + "\"layer1\":\"#layer0\",\"particle\":\"minecraft:item/gold_nugget\"}}",
                "assets/hcm/items/coin.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"hcm:item/nothing\"}}"));
        List<String> warnings = result.warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("parent hcm:item/base")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("texture hcm:item/coin")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("hcm:item/nothing")), warnings.toString());
        assertFalse(warnings.stream().anyMatch(w -> w.contains("gold_nugget") || w.contains("#layer0")),
                "references to vanilla and to the model's own variables are fine");
    }

    @Test
    void thePacksOwnMcmetaIsDroppedAndOneIsWrittenForTheServersFormat() {
        PackAssembler.Result result = assemble(source("lilahcraft-clocks",
                "pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"clocks\"},\"overlays\":{\"entries\":[]}}",
                "overlay_1/assets/minecraft/items/clock.json", "{}",
                "assets/minecraft/textures/item/clock_00.png", "PNG"));
        JsonObject pack = json(result, "pack.mcmeta").getAsJsonObject("pack");
        assertEquals("[97,0]", pack.get("min_format").toString());
        assertEquals("[97,1]", pack.get("max_format").toString());
        assertNull(pack.get("pack_format"));
        assertFalse(result.files().keySet().stream().anyMatch(p -> p.startsWith("overlay_1/")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("overlays")), result.warnings().toString());
    }

    @Test
    void aPackZippedInsideAFolderIsReadFromThatFolder() {
        PackAssembler.Result result = assemble(source("lilahcraft-clocks",
                "Clocks v2/pack.mcmeta", "{}", "Clocks v2/assets/minecraft/textures/item/clock_00.png", "PNG"));
        assertTrue(result.files().containsKey("assets/minecraft/textures/item/clock_00.png"));
    }

    @Test
    void upperCaseNamesAreLeftOutWithAWarning() {
        PackAssembler.Result result = assemble(source("hcm", "assets/hcm/textures/Coin.png", "PNG"));
        assertFalse(result.files().containsKey("assets/hcm/textures/Coin.png"));
        assertEquals(1, result.warnings().size());
    }

    @Test
    void glyphsBecomeBitmapsInTheDefaultFontNextToTheSpaces() {
        PackAssembler.Result result = assemble(
                source("hcm", "glyphs.json", "{\"glyphs\":{\"hcm:coin\":{\"texture\":\"hcm:font/coin.png\",\"height\":9,\"ascent\":8},"
                                + "\"hcm:missing\":\"hcm:font/nope\"}}",
                        "assets/hcm/textures/font/coin.png", "PNG",
                        "assets/minecraft/font/default.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"hcm:font/coin.png\","
                                + "\"ascent\":7,\"height\":8,\"chars\":[\"\\ue000\"]}]}"));
        JsonArray providers = json(result, PackAssembler.GLYPH_FONT).getAsJsonArray("providers");
        JsonObject coin = providers.get(0).getAsJsonObject();
        assertEquals("bitmap", coin.get("type").getAsString());
        assertEquals("hcm:font/coin.png", coin.get("file").getAsString());
        assertEquals(9, coin.get("height").getAsInt());
        assertEquals(8, coin.get("ascent").getAsInt());
        int code = assigned.get("hcm:coin");
        assertEquals(Glyphs.text(code), coin.getAsJsonArray("chars").get(0).getAsString());
        assertEquals("space", providers.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(-1, providers.get(1).getAsJsonObject().getAsJsonObject("advances").get("\uF801").getAsInt());
        assertEquals(3, providers.size(), "the source's own provider follows CraftBridge's");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("U+E000")), "its character clashes with hcm:coin");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("hcm:missing")), result.warnings().toString());
        assertEquals(2, result.glyphs().size());
        assertTrue(result.glyphs().stream().anyMatch(g -> g.key().equals("hcm:missing") && !g.drawn()));
        assertEquals(2, result.sources().get(0).glyphs());
    }

    @Test
    void theSpacesAreThereWithoutAnyGlyphs() {
        PackAssembler.Result result = assemble(source("craftbridge", "assets/craftbridge/textures/a.png", "PNG"));
        JsonArray providers = json(result, PackAssembler.GLYPH_FONT).getAsJsonArray("providers");
        assertEquals(1, providers.size());
        assertEquals(Glyphs.SPACES.size(), providers.get(0).getAsJsonObject().getAsJsonObject("advances").size());
    }

    @Test
    void aGlyphDeclaredTwiceIsAConflict() {
        PackAssembler.Result result = assemble(
                source("hcm", "glyphs.json", "{\"glyphs\":{\"shared:star\":\"hcm:font/star\"}}", "assets/hcm/textures/font/star.png", "PNG"),
                source("collins", "glyphs.json", "{\"glyphs\":{\"shared:star\":\"collins:font/star\"}}"));
        assertEquals(1, result.errors().size());
        assertEquals("hcm", result.glyphs().get(0).source());
    }

    @Test
    void theIconComesFromTheHighestSourceThatHasOne() {
        PackAssembler.Result result = assemble(source("lilahcraft-clocks", "pack.png", "clocks"),
                source("craftbridge", "pack.png", "craftbridge"));
        assertArrayEquals(b("craftbridge"), result.files().get("pack.png"));
    }

    @Test
    void aNamespaceSharedBetweenSourcesIsWarnedAbout() {
        PackAssembler.Result result = assemble(source("hcm", "assets/lilahcraft/textures/a.png", "PNG"),
                source("lilahcraft-clocks", "assets/lilahcraft/textures/b.png", "PNG"));
        List<String> namespaceWarnings = new ArrayList<>(result.warnings());
        namespaceWarnings.removeIf(w -> !w.contains("namespace 'lilahcraft'"));
        assertEquals(1, namespaceWarnings.size(), result.warnings().toString());
    }
}
