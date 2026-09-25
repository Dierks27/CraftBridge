package com.dierks.craftbridge.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "never erase what did not load" half of the data stores: {@link KeptEntries} and {@link SafeYaml}. */
class KeptEntriesTest {

    private static final String FILE = """
            recipes:
              good:
                type: shaped
                shape: [AB]
              Broken_One:
                type: shaped
                result: {custom: deleted_item, amount: 3}
                shape: ['A A', ' B ']
                ingredients:
                  A: {material: STICK}
                  B: {tag: 'minecraft:wool'}
              scalar: 42
            """;

    private static YamlConfiguration parse(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    /** Simulate a store's save: write the {@code live} ids, then the kept entries, and read the result back. */
    private static YamlConfiguration save(KeptEntries kept, Set<String> live) throws InvalidConfigurationException {
        YamlConfiguration out = new YamlConfiguration();
        for (String id : live) {
            out.set("recipes." + id + ".type", "shapeless");
        }
        ConfigurationSection root = out.getConfigurationSection("recipes");
        kept.writeInto(root == null ? out.createSection("recipes") : root, live);
        return parse(out.saveToString());
    }

    @Test
    void unreadableEntriesAreWrittenBackVerbatim() throws Exception {
        YamlConfiguration loaded = parse(FILE);
        ConfigurationSection recipes = loaded.getConfigurationSection("recipes");
        KeptEntries kept = new KeptEntries();
        kept.keep("Broken_One", recipes.getConfigurationSection("Broken_One"));
        kept.keep("scalar", recipes.get("scalar"));

        YamlConfiguration saved = save(kept, Set.of("good"));

        assertEquals(Set.of("good", "Broken_One", "scalar"), saved.getConfigurationSection("recipes").getKeys(false));
        assertEquals(recipes.getConfigurationSection("Broken_One").getValues(true).toString(),
                saved.getConfigurationSection("recipes.Broken_One").getValues(true).toString());
        assertEquals(42, saved.getInt("recipes.scalar"));
        assertEquals(List.of("A A", " B "), saved.getStringList("recipes.Broken_One.shape"));
        assertEquals("deleted_item", saved.getString("recipes.Broken_One.result.custom"));
        assertEquals(3, saved.getInt("recipes.Broken_One.result.amount"));
    }

    @Test
    void aKeptEntrySurvivesSeveralSaves() throws Exception {
        KeptEntries kept = new KeptEntries();
        kept.keep("Broken_One", parse(FILE).getConfigurationSection("recipes.Broken_One"));
        YamlConfiguration first = save(kept, Set.of("good"));

        // Next session: the same entry fails again and is kept from the file we just wrote.
        KeptEntries again = new KeptEntries();
        again.keep("Broken_One", first.getConfigurationSection("recipes.Broken_One"));
        YamlConfiguration second = save(again, Set.of("good", "new_one"));

        assertEquals(first.getConfigurationSection("recipes.Broken_One").getValues(true).toString(),
                second.getConfigurationSection("recipes.Broken_One").getValues(true).toString());
    }

    @Test
    void theKeptCopyIsIndependentOfTheLoadedFile() throws Exception {
        YamlConfiguration loaded = parse(FILE);
        KeptEntries kept = new KeptEntries();
        kept.keep("Broken_One", loaded.getConfigurationSection("recipes.Broken_One"));
        loaded.set("recipes.Broken_One.type", "furnace");
        Object copy = kept.get("Broken_One");
        assertTrue(copy instanceof Map<?, ?>);
        assertEquals("shaped", ((Map<?, ?>) copy).get("type"));
    }

    @Test
    void savingOrDeletingTheSameIdForgetsTheKeptCopyIgnoringCase() {
        KeptEntries kept = new KeptEntries();
        kept.keep("Broken_One", Map.of("type", "shaped"));
        kept.keep("other", Map.of("type", "shaped"));
        kept.forget("broken_one");
        assertEquals(Set.of("other"), kept.keys());
        kept.forget("OTHER");
        assertTrue(kept.isEmpty());
    }

    @Test
    void aLiveEntryWithTheSameIdWins() throws Exception {
        KeptEntries kept = new KeptEntries();
        kept.keep("Good", Map.of("type", "broken"));
        YamlConfiguration saved = save(kept, Set.of("good"));
        assertEquals(Set.of("good"), saved.getConfigurationSection("recipes").getKeys(false));
        assertEquals("shapeless", saved.getString("recipes.good.type"));
    }

    @Test
    void nothingKeptWritesNothingExtra() throws Exception {
        YamlConfiguration saved = save(new KeptEntries(), Set.of("a"));
        assertEquals(Set.of("a"), saved.getConfigurationSection("recipes").getKeys(false));
    }

    // ---- SafeYaml ---------------------------------------------------------------

    private static final class Capture extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger logger(Capture capture) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        return logger;
    }

    @Test
    void aMissingFileLoadsEmpty(@TempDir File dir) {
        Capture capture = new Capture();
        YamlConfiguration yaml = SafeYaml.loadOrNull(new File(dir, "recipes.yml"), logger(capture));
        assertNotNull(yaml);
        assertTrue(yaml.getKeys(false).isEmpty());
        assertTrue(capture.records.isEmpty());
    }

    @Test
    void aValidFileLoads(@TempDir File dir) throws IOException {
        File file = new File(dir, "recipes.yml");
        Files.writeString(file.toPath(), FILE, StandardCharsets.UTF_8);
        YamlConfiguration yaml = SafeYaml.loadOrNull(file, logger(new Capture()));
        assertNotNull(yaml);
        assertEquals(Set.of("good", "Broken_One", "scalar"), yaml.getConfigurationSection("recipes").getKeys(false));
    }

    @Test
    void aSyntaxErrorIsReportedNotTurnedIntoAnEmptyFile(@TempDir File dir) throws IOException {
        File file = new File(dir, "recipes.yml");
        // A tab-indented hand edit is invalid YAML; loadConfiguration would return an empty config.
        Files.writeString(file.toPath(), "recipes:\n  good:\n\ttype: shaped\n", StandardCharsets.UTF_8);
        Capture capture = new Capture();
        assertNull(SafeYaml.loadOrNull(file, logger(capture)));
        assertFalse(capture.records.isEmpty());
        assertEquals(Level.SEVERE, capture.records.get(0).getLevel());
        assertTrue(capture.records.get(0).getMessage().contains("recipes.yml"));
    }

    @Test
    void aTopLevelThatIsNotAMapIsAFailureToo(@TempDir File dir) throws IOException {
        File file = new File(dir, "recipes.yml");
        Files.writeString(file.toPath(), "- just\n- a list\n", StandardCharsets.UTF_8);
        assertNull(SafeYaml.loadOrNull(file, logger(new Capture())));
    }
}
