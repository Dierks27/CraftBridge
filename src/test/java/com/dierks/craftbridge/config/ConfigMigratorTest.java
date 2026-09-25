package com.dierks.craftbridge.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrading is "drop in the jar": an old config.yml gains what the jar added, keeps
 * everything the admin set, and is left alone from then on.
 *
 * <p>The fixtures are the bundled config.yml of real releases: v0.1.0 predates the Combo
 * Chest, phantom slots and the client link (the live server's case: "combo-chest.recipe.shape
 * is unusable — row 1 uses 'H', which has no entry under ingredients"), and v0.13 is the last
 * file without {@code minecraft:brewing}.
 */
class ConfigMigratorTest {

    private static final String TYPES = "jei.recipe-sync.types";

    @TempDir
    Path dir;

    private Path config;
    private final List<String> logged = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    @BeforeEach
    void captureLog() {
        config = dir.resolve("config.yml");
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    // ---- the 0.13 file (the live server's version) -------------------------------------

    @Test
    void aV013FileGainsBrewingOnceAndKeepsEverythingTheAdminSet() throws IOException {
        String original = fixture("config-v0.13.yml")
                .replace("  default-trigger: DOUBLE_CLICK_OUTSIDE", "  default-trigger: COMMAND_ONLY")
                .replace("combo-chest:\n  radius: 8", "combo-chest:\n  # the admin's own note\n  radius: 12");
        Files.writeString(config, original);

        ConfigMigrator.Result result = migrate();

        assertEquals(0, result.from());
        assertEquals(ConfigMigrator.CURRENT_VERSION, result.to());
        YamlConfiguration after = reload();
        List<String> types = after.getStringList(TYPES);
        assertEquals("minecraft:brewing", types.get(types.size() - 1), "appended at the end");
        assertEquals(1, types.stream().filter("minecraft:brewing"::equals).count());
        assertEquals(8, types.size());
        assertEquals("COMMAND_ONLY", after.getString("sorting.default-trigger"), "admin value untouched");
        assertEquals(12, after.getInt("combo-chest.radius"), "admin value untouched");
        assertEquals(ConfigMigrator.CURRENT_VERSION, after.getInt(ConfigMigrator.VERSION_KEY));

        String text = Files.readString(config);
        assertTrue(text.contains("# the admin's own note"), "the admin's comment survives");
        assertTrue(text.contains("# Master switches. Each feature is independent"), "bundled comments survive");
        assertTrue(text.contains("# Recipe types sent to JEI clients"), "comment on the list key survives");
        assertTrue(text.contains("# Which layout this file has."), "the version key gets its comment");

        Path backup = dir.resolve("config.yml.bak-v0");
        assertEquals(original, Files.readString(backup), "the backup is the file exactly as it was");

        assertEquals(1, logged.size(), logged.toString());
        String line = logged.get(0);
        assertTrue(line.startsWith("INFO Config migrated v0 → v" + ConfigMigrator.CURRENT_VERSION + ": "), line);
        assertTrue(line.contains("added " + TYPES + " [minecraft:brewing]"), line);
        assertTrue(line.contains("config.yml.bak-v0"), line);
    }

    @Test
    void aSecondBootChangesNothing() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml"));
        migrate();
        byte[] migrated = Files.readAllBytes(config);
        byte[] backup = Files.readAllBytes(dir.resolve("config.yml.bak-v0"));
        logged.clear();

        ConfigMigrator.Result second = migrate();

        assertFalse(second.migrated());
        assertArrayEquals(migrated, Files.readAllBytes(config), "file untouched");
        assertArrayEquals(backup, Files.readAllBytes(dir.resolve("config.yml.bak-v0")), "backup untouched");
        assertEquals(Set.of("config.yml", "config.yml.bak-v0"), fileNames(), "no new files");
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void aTypeTheAdminRemovesStaysRemoved() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml"));
        migrate();
        String withoutBrewing = Files.readString(config).replaceAll("(?m)^ *- minecraft:brewing\n", "");
        Files.writeString(config, withoutBrewing);

        migrate();
        migrate();

        assertFalse(reload().getStringList(TYPES).contains("minecraft:brewing"));
        assertEquals(withoutBrewing, Files.readString(config));
    }

    @Test
    void aTypeRemovedBeforeTheUpgradeIsNotReAdded() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace("      - minecraft:smithing\n", ""));

        migrate();

        List<String> types = reload().getStringList(TYPES);
        assertFalse(types.contains("minecraft:smithing"), "only entries new in the step are appended");
        assertTrue(types.contains("minecraft:brewing"));
    }

    @Test
    void anEmptyTypeListMeansAllBuiltInsAndIsLeftEmpty() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replaceAll(
                "(?s)    types:\n(      - [^\n]+\n)+", "    types: []\n"));

        migrate();

        assertTrue(reload().getStringList(TYPES).isEmpty());
    }

    @Test
    void aTypeAlreadyThereIsNotDuplicated() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace(
                "      - minecraft:smithing\n", "      - minecraft:smithing\n      - ' Minecraft:Brewing '\n"));

        migrate();

        List<String> types = reload().getStringList(TYPES);
        assertEquals(1, types.stream().filter(t -> t.trim().equalsIgnoreCase("minecraft:brewing")).count(), types.toString());
    }

    // ---- the admin's text is kept when only lines need adding -----------------------

    @Test
    void aV013UpgradeOnlyInsertsLinesAndKeepsEveryOtherByte() throws IOException {
        String original = fixture("config-v0.13.yml").replace(
                "      - minecraft:smelting\n", "      # - minecraft:smelting   (the admin turned this off)\n");
        Files.writeString(config, original);

        migrate();

        String expected = withNewSettings(original.replace("      - minecraft:smithing\n",
                "      - minecraft:smithing\n      - minecraft:brewing\n"))
                + "\n" + versionBlock() + "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n";
        assertEquals(expected, Files.readString(config));
        assertTrue(Files.readString(config).contains("shape: ['HEH', 'CBC', 'HRH']"), "flow lists kept as written");
        assertFalse(reload().getStringList(TYPES).contains("minecraft:smelting"));
    }

    @Test
    void aV0131FileOnlyGainsTheNewSectionsAndTheVersionStamp() throws IOException {
        String original = fixture("config-v0.13.1.yml");
        Files.writeString(config, original);

        migrate();

        String after = Files.readString(config);
        assertTrue(after.startsWith(withNewSettings(original).stripTrailing()), "nothing else before the stamp changed");
        assertTrue(after.contains("# Minecraft 26.3 made brewing a recipe type"), "a comment inside a list survives");
        assertTrue(after.contains("# Golem chests"), "the new section keeps the jar's comments");
        assertEquals(1, reload().getStringList(TYPES).stream().filter("minecraft:brewing"::equals).count());
        assertTrue(logged.get(0).contains("added sorting.middle-click, "), logged.get(0));
        assertTrue(logged.get(0).contains(", golem-chests, "), logged.get(0));
    }

    @Test
    void aNewSectionLandsBeforeTheNextOneTheFileHas() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml"));

        migrate();

        String after = Files.readString(config);
        int golem = after.indexOf("golem-chests:");
        assertTrue(golem > after.indexOf("combo-chest:") && golem < after.indexOf("\njei:"), after);
        assertEquals(List.of("C", "H", "S"), reload().getStringList("golem-chests.recipe.shape"));
        assertEquals("HONEYCOMB", reload().getString("golem-chests.recipe.ingredients.H"));
    }

    @Test
    void aFileWithoutTheNextSectionStillGetsTheNewOneThroughTheYamlWriter() throws IOException {
        String original = fixture("config-v0.13.yml");
        Files.writeString(config, original.substring(0, original.indexOf("# ---------------------------------"
                + "--------------------------------------------\n# JEI")));

        migrate();

        assertTrue(reload().getBoolean("golem-chests.enabled"));
        assertEquals(ConfigMigrator.CURRENT_VERSION, reload().getInt(ConfigMigrator.VERSION_KEY));
    }

    @Test
    void aFlowStyleListStillGetsItsEntryThroughTheYamlWriter() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replaceAll("(?s)    types:\n(      - [^\n]+\n)+",
                "    types: [minecraft:crafting, minecraft:smelting]\n"));

        migrate();

        assertEquals(List.of("minecraft:crafting", "minecraft:smelting", "minecraft:brewing"), reload().getStringList(TYPES));
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreKeptToo() throws IOException {
        String original = "\uFEFF" + fixture("config-v0.13.yml").replace("\n", "\r\n");
        Files.writeString(config, original);

        migrate();

        String expected = (withNewSettings(fixture("config-v0.13.yml").replace("      - minecraft:smithing\n",
                "      - minecraft:smithing\n      - minecraft:brewing\n"))
                + "\n" + versionBlock() + "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n").replace("\n", "\r\n");
        assertEquals("\uFEFF" + expected, Files.readString(config));
    }

    @Test
    void aCommentUnderTheLastItemStaysWithIt() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace("      - minecraft:smithing\n",
                "      - minecraft:smithing\n        # ^ needs 1.20+\n"));

        migrate();

        assertTrue(Files.readString(config).contains(
                "      - minecraft:smithing\n        # ^ needs 1.20+\n      - minecraft:brewing\n"), Files.readString(config));
    }

    @Test
    void anExistingVersionLineIsUpdatedInPlaceWithItsComment() throws IOException {
        String original = fixture("config-v0.13.yml") + "\n'config-version': 1 # set by hand\n";
        Files.writeString(config, original);

        migrate();

        String after = Files.readString(config);
        assertTrue(after.endsWith("'config-version': " + ConfigMigrator.CURRENT_VERSION + " # set by hand\n"), after);
        assertEquals(1, after.split("config-version'?:", -1).length - 1, "one version line: " + after);
    }

    @Test
    void trailingBlankLinesAndAnExistingVersionBlockAreNotDoubled() throws IOException {
        String withBlock = fixture("config-v0.13.yml") + "\n" + versionBlock() + "\n\n";
        Files.writeString(config, withBlock);

        migrate();

        String after = Files.readString(config);
        assertEquals(1, after.split("Which layout this file has", -1).length - 1, after);
        assertTrue(after.contains(versionBlock() + "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n"), after);
    }

    @Test
    void aSelfReferencingAnchorCannotCrashTheBoot() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml") + "loop: &a [*a]\n");

        migrate(); // must not throw: the hand edit is abandoned and the YAML writer used

        assertTrue(reload().getStringList(TYPES).contains("minecraft:brewing"));
    }

    @Test
    void aBadTagIsLoggedNotThrown() throws IOException {
        String bad = fixture("config-v0.13.yml") + "oops: !!int abc\n";
        Files.writeString(config, bad);

        assertFalse(migrate().migrated());
        assertEquals(bad, Files.readString(config));
        assertTrue(logged.get(0).startsWith("SEVERE"), logged.toString());
    }

    @Test
    void aFileThatIsNotUtf8IsLeftAloneWithAWarning() throws IOException {
        byte[] latin1 = (fixture("config-v0.13.yml") + "# caf\u00e9\n").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(config, latin1);

        ConfigMigrator.Result result = migrate();

        assertFalse(result.migrated());
        assertArrayEquals(latin1, Files.readAllBytes(config));
        assertEquals(Set.of("config.yml"), fileNames());
        assertTrue(logged.get(0).startsWith("WARNING") && logged.get(0).contains("not UTF-8"), logged.toString());
    }

    @Test
    void aSymlinkedConfigStaysALink() throws IOException {
        Path real = Files.createDirectory(dir.resolve("shared")).resolve("craftbridge.yml");
        Files.writeString(real, fixture("config-v0.13.yml"));
        Files.createSymbolicLink(config, real);

        migrate();

        assertTrue(Files.isSymbolicLink(config), "the link is kept");
        assertTrue(Files.readString(real).contains("minecraft:brewing"), "its target was upgraded");
    }

    // ---- the v0.1.0 file: no combo-chest section at all --------------------------------

    @Test
    void aFileFromBeforeTheComboChestGetsTheWholeSectionAndAUsableRecipe() throws IOException {
        Files.writeString(config, fixture("config-v0.1.0.yml"));

        migrate();

        YamlConfiguration after = reload();
        ConfigurationSection ingredients = after.getConfigurationSection("combo-chest.recipe.ingredients");
        assertNotNull(ingredients);
        assertEquals(Set.of("H", "E", "C", "B", "R"), ingredients.getKeys(false));
        List<String> shape = after.getStringList("combo-chest.recipe.shape");
        assertNull(ShapeSpec.problem(shape, symbols(ingredients)), "the startup warning is gone");
        assertTrue(after.getBoolean("features.client-link"));
        assertTrue(after.getBoolean("linked-workbench.phantom-slots"));
        assertTrue(Files.readString(config).contains("# H = chest, E = ender pearl"), "section comments copied");
        String line = logged.get(0);
        assertTrue(line.contains("combo-chest"), line);
        assertFalse(line.contains("combo-chest.radius"), "a missing section is named once, not leaf by leaf: " + line);
    }

    @Test
    void anEmptyIngredientMapWrittenToDiskIsRepaired() throws IOException {
        // What an old file looked like after any "/craftbridge combochest display" tweak saved
        // the empty section getConfigurationSection had created in memory.
        Files.writeString(config, fixture("config-v0.1.0.yml")
                + "combo-chest:\n  recipe:\n    ingredients: {}\n  display:\n    scale: 1.5\n");

        migrate();

        YamlConfiguration after = reload();
        assertEquals(Set.of("H", "E", "C", "B", "R"),
                after.getConfigurationSection("combo-chest.recipe.ingredients").getKeys(false));
        assertEquals(List.of("HEH", "CBC", "HRH"), after.getStringList("combo-chest.recipe.shape"));
        assertEquals(1.5, after.getDouble("combo-chest.display.scale"), "admin value untouched");
        assertEquals(1.005, after.getDouble("combo-chest.display.offset-y"), "missing sibling added");
        assertTrue(logged.get(0).contains("repaired combo-chest.recipe.ingredients"), logged.get(0));
    }

    @Test
    void aCustomisedRecipeIsNeverMergedWithTheJarsLetters() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace(
                "    shape: ['HEH', 'CBC', 'HRH']\n    ingredients:\n      H: CHEST\n      E: ENDER_PEARL\n"
                        + "      C: COPPER_INGOT\n      B: BARREL\n      R: COMPARATOR\n",
                "    shape: ['XXX', 'XBX', 'XXX']\n    ingredients:\n      X: DIAMOND\n      B: BARREL\n"));

        migrate();

        YamlConfiguration after = reload();
        assertEquals(Set.of("X", "B"), after.getConfigurationSection("combo-chest.recipe.ingredients").getKeys(false));
        assertEquals(List.of("XXX", "XBX", "XXX"), after.getStringList("combo-chest.recipe.shape"));
    }

    @Test
    void anEmptyMapWithACustomShapeIsLeftForShapeSpecToReport() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace(
                "    shape: ['HEH', 'CBC', 'HRH']\n    ingredients:\n      H: CHEST\n      E: ENDER_PEARL\n"
                        + "      C: COPPER_INGOT\n      B: BARREL\n      R: COMPARATOR\n",
                "    shape: ['XXX', 'XBX', 'XXX']\n    ingredients: {}\n"));

        migrate();

        YamlConfiguration after = reload();
        assertTrue(after.getConfigurationSection("combo-chest.recipe.ingredients").getKeys(false).isEmpty(),
                "the jar's H/E/C letters would not fix an X shape");
    }

    @Test
    void aScalarWhereTheJarHasASectionIsNotClobbered() throws IOException {
        Files.writeString(config, fixture("config-v0.1.0.yml") + "combo-chest: false\n");

        migrate();

        assertEquals(Boolean.FALSE, reload().get("combo-chest"));
    }

    @Test
    void aBlankedWorkbenchTextureStaysBlank() throws IOException {
        // Missing means "show display-item"; the jar's value would put a textured head there.
        String original = fixture("config-v0.13.yml");
        int start = original.indexOf("  head-texture: \"eyJ0");
        String edited = original.substring(0, start) + original.substring(original.indexOf('\n', start) + 1);
        Files.writeString(config, edited.replace("linked-workbench:\n", "linked-workbench:\n  display-item: CRAFTING_TABLE\n"));
        assertFalse(Files.readString(config).contains("NDkzZDExMmRj"), "fixture edit applied");

        migrate();

        assertNull(reload().get("linked-workbench.head-texture", null));
    }

    @Test
    void anEmptyMapWithAShapeOfStockLettersGetsTheStockIngredients() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml").replace(
                "    shape: ['HEH', 'CBC', 'HRH']\n    ingredients:\n      H: CHEST\n      E: ENDER_PEARL\n"
                        + "      C: COPPER_INGOT\n      B: BARREL\n      R: COMPARATOR\n",
                "    shape: ['HHH', 'CBC', 'ERE']\n    ingredients: {}\n"));

        migrate();

        YamlConfiguration after = reload();
        ConfigurationSection ingredients = after.getConfigurationSection("combo-chest.recipe.ingredients");
        assertEquals(List.of("HHH", "CBC", "ERE"), after.getStringList("combo-chest.recipe.shape"), "shape untouched");
        assertNull(ShapeSpec.problem(after.getStringList("combo-chest.recipe.shape"), symbols(ingredients)));
    }

    @Test
    void permissionsAreKept() throws IOException {
        Files.writeString(config, fixture("config-v0.13.yml"));
        var mode = java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----");
        try {
            Files.setPosixFilePermissions(config, mode);
        } catch (UnsupportedOperationException notPosix) {
            return;
        }

        migrate();

        assertEquals(mode, Files.getPosixFilePermissions(config));
    }

    /**
     * A file at the current version is never looked at again, so a setting added to the jar's
     * config.yml without bumping {@link ConfigMigrator#CURRENT_VERSION} would never reach an
     * existing server. If this fails because you changed config.yml: bump CURRENT_VERSION,
     * add a Step if you added entries to an existing list, and update keys-v&lt;n&gt;.txt.
     */
    @Test
    void theBundledKeysMatchTheCurrentVersion() throws IOException {
        YamlConfiguration bundled = new YamlConfiguration();
        try {
            bundled.loadFromString(bundledText());
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new AssertionError(ex);
        }
        assertEquals(List.of(fixture("keys-v" + ConfigMigrator.CURRENT_VERSION + ".txt").strip().split("\n")),
                new ArrayList<>(bundled.getKeys(true)), "config.yml changed: bump CURRENT_VERSION");
        assertEquals(List.of("minecraft:crafting", "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
                "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing", "minecraft:brewing"),
                bundled.getStringList(TYPES), "a new list entry needs a version bump and a Step");
    }

    // ---- a v3 file (golem chests and middle-click sorting, before the model displays) ----

    @Test
    void aV3FileGainsTheModelSettingsAndThePackSectionsAndNothingElse() throws IOException {
        String original = fixture("config-v3.yml");
        Files.writeString(config, original);

        ConfigMigrator.Result result = migrate();

        assertEquals(3, result.from());
        String expected = withV4Settings(original.replace("config-version: 3\n",
                "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n"));
        assertEquals(expected, Files.readString(config), "only lines inserted, every other byte kept");
        assertEquals(List.of("added linked-workbench.display.mode, linked-workbench.display.model-scale,"
                + " combo-chest.display.mode, combo-chest.display.model-scale, resource-pack, bedrock"), result.changes());

        YamlConfiguration after = reload();
        assertEquals("model", after.getString("linked-workbench.display.mode"));
        assertEquals("model", after.getString("combo-chest.display.mode"));
        assertEquals(1.002, after.getDouble("combo-chest.display.model-scale"), 1e-9);
        assertTrue(after.getBoolean("resource-pack.enabled"));
        assertFalse(after.getBoolean("resource-pack.required"));
        assertEquals("", after.getString("resource-pack.url"));
        assertFalse(after.getBoolean("resource-pack.host.enabled"), "the built-in web server stays off");
        assertEquals(8765, after.getInt("resource-pack.host.port"));
        assertEquals("", after.getString("resource-pack.host.public-address"));
        assertFalse(after.getBoolean("bedrock.show-displays"));
        String text = Files.readString(config);
        assertTrue(text.contains("# Resource pack — the models behind display.mode: model."), "section comment kept");
        assertTrue(text.contains("    # The address players download from"), "nested comment kept");
        assertTrue(text.contains("  #   url: \"https://www.lilahcraft.com/craftbridge/craftbridge-java.zip\""), "the example url");
        assertTrue(text.indexOf("\nbedrock:") > text.indexOf("\nresource-pack:")
                && text.indexOf("\nbedrock:") < text.indexOf("\njei:"), "jar order kept");
    }

    @Test
    void aV3FileKeepsTheAdminsHeadGeometryAndGetsTheNewKeysBesideIt() throws IOException {
        String original = fixture("config-v3.yml").replaceFirst("    scale: 2.02\n", "    scale: 3.5\n");
        Files.writeString(config, original);

        migrate();

        YamlConfiguration after = reload();
        assertEquals(3.5, after.getDouble("linked-workbench.display.scale"), 1e-9, "admin value untouched");
        assertEquals(2.02, after.getDouble("combo-chest.display.scale"), 1e-9);
        assertEquals("model", after.getString("linked-workbench.display.mode"));
        assertEquals(List.of("mode", "model-scale", "transform", "scale", "offset-x", "offset-y", "offset-z", "yaw-offset"),
                new ArrayList<>(after.getConfigurationSection("linked-workbench.display").getKeys(false)));
    }

    @Test
    void aV3FileThatAlreadyChoseHeadModeKeepsIt() throws IOException {
        Files.writeString(config, fixture("config-v3.yml").replace(
                "  display:\n    transform: NONE\n", "  display:\n    mode: head\n    transform: NONE\n"));

        migrate();

        YamlConfiguration after = reload();
        assertEquals("head", after.getString("linked-workbench.display.mode"));
        assertEquals("head", after.getString("combo-chest.display.mode"));
        assertEquals(1.002, after.getDouble("linked-workbench.display.model-scale"), 1e-9);
    }

    // ---- files that must not be touched ---------------------------------------------

    @Test
    void theJarsOwnConfigIsAlreadyCurrent() throws IOException {
        String bundled = bundledText();
        Files.writeString(config, bundled);

        ConfigMigrator.Result result = migrate();

        assertFalse(result.migrated());
        assertEquals(bundled, Files.readString(config));
        assertEquals(Set.of("config.yml"), fileNames());
    }

    @Test
    void aNewerFileIsLeftAloneWithAWarning() throws IOException {
        String newer = fixture("config-v0.13.yml") + "config-version: 99\n";
        Files.writeString(config, newer);

        migrate();

        assertEquals(newer, Files.readString(config));
        assertTrue(logged.get(0).startsWith("WARNING"), logged.toString());
    }

    @Test
    void brokenYamlIsNeverOverwritten() throws IOException {
        String broken = "features:\n  sorting: true\n   recipes: [unclosed\n";
        Files.writeString(config, broken);

        ConfigMigrator.Result result = migrate();

        assertFalse(result.migrated());
        assertEquals(broken, Files.readString(config));
        assertEquals(Set.of("config.yml"), fileNames(), "no backup, no temp file");
        assertTrue(logged.get(0).startsWith("SEVERE"), logged.toString());
    }

    @Test
    void noFileMeansNothingToDo() throws IOException {
        assertFalse(migrate().migrated());
        assertFalse(Files.exists(config));
    }

    @Test
    void anExistingBackupIsNeverOverwritten() throws IOException {
        Files.writeString(dir.resolve("config.yml.bak-v0"), "the true original\n");
        Files.writeString(config, fixture("config-v0.13.yml"));

        migrate();

        assertEquals("the true original\n", Files.readString(dir.resolve("config.yml.bak-v0")));
        assertTrue(logged.get(0).contains("kept the existing backup"), logged.get(0));
    }

    // ---- helpers ---------------------------------------------------------------------

    private ConfigMigrator.Result migrate() throws IOException {
        try (Reader bundled = new InputStreamReader(resource("/config.yml"), StandardCharsets.UTF_8)) {
            return ConfigMigrator.migrate(config, bundled, log);
        }
    }

    private YamlConfiguration reload() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(config));
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new AssertionError("migrated file does not parse", ex);
        }
        return yaml;
    }

    private Set<String> fileNames() throws IOException {
        Set<String> names = new HashSet<>();
        try (var files = Files.list(dir)) {
            files.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    private static Set<Character> symbols(ConfigurationSection ingredients) {
        Set<Character> out = new HashSet<>();
        for (String key : ingredients.getKeys(false)) {
            out.add(key.charAt(0));
        }
        return out;
    }

    /** Fixtures as LF text, whatever line endings the checkout gave them. */
    private static String fixture(String name) throws IOException {
        return new String(resource("/config-migration/" + name).readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    /** The comment block the jar puts above config-version, as it appears in the file. */
    private static String versionBlock() throws IOException {
        String bundled = bundledText();
        int key = bundled.indexOf("\n" + ConfigMigrator.VERSION_KEY + ":");
        int start = bundled.lastIndexOf("\n\n", key) + 2;
        return bundled.substring(start, key + 1);
    }

    /**
     * A 0.13 file's text as an upgrade to the current version leaves it: the settings v3 and
     * v4 added, copied from the jar with their comments, just before the next setting the
     * file already has.
     */
    private static String withNewSettings(String text) throws IOException {
        return withV4Settings(withV3Sections(text));
    }

    private static String withV3Sections(String text) throws IOException {
        String header = "# -----------------------------------------------------------------------------\n";
        String jar = bundledText();
        String golem = jar.substring(jar.indexOf(header + "# Golem chests"), jar.indexOf(header + "# Resource pack"));
        int at = text.indexOf(header + "# JEI");
        text = text.substring(0, at) + golem + text.substring(at);
        String feedback = "  # Default for the sound/particle feedback toggle.\n";
        String middle = jar.substring(jar.indexOf("  # Middle-click to sort"), jar.indexOf(feedback));
        at = text.indexOf(feedback);
        return text.substring(0, at) + middle + text.substring(at);
    }

    /**
     * A v3 file's text as the v4 upgrade leaves it: display.mode and display.model-scale in
     * both block sections (just before transform), and the resource-pack and bedrock sections
     * just before the JEI section, all as the jar writes them.
     */
    private static String withV4Settings(String text) throws IOException {
        String jar = bundledText();
        String display = "  display:\n";
        String transform = "    transform: NONE\n";
        int from = 0;
        for (String block : List.of("linked-workbench:", "combo-chest:")) {
            int jarBlock = jar.indexOf("\n" + block + "\n");
            int jarDisplay = jar.indexOf(display, jarBlock) + display.length();
            int jarHeadOnly = jar.indexOf("    # The settings below shape the head", jarDisplay);
            String added = jar.substring(jarDisplay, jarHeadOnly);
            int at = text.indexOf(display + transform, text.indexOf("\n" + block + "\n", from)) + display.length();
            text = text.substring(0, at) + added + text.substring(at);
            from = at;
        }
        String header = "# -----------------------------------------------------------------------------\n";
        String sections = jar.substring(jar.indexOf(header + "# Resource pack"), jar.indexOf(header + "# JEI"));
        int at = text.indexOf(header + "# JEI");
        return text.substring(0, at) + sections + text.substring(at);
    }

    private static String bundledText() throws IOException {
        return new String(resource("/config.yml").readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static InputStream resource(String path) {
        InputStream in = ConfigMigratorTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new AssertionError("missing test resource " + path);
        }
        return in;
    }
}
