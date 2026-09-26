package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled vanilla tables and how the plugin reads and picks them. */
class VanillaItemsTest {

    static final Path VANILLA = Path.of("src/main/resources/resourcepack/vanilla");

    static VanillaItems table(String version) throws IOException {
        return VanillaItems.parse(Files.readAllBytes(VANILLA.resolve("items-" + version + ".json")));
    }

    @Test
    void thereIsATableForEachVersionThePackSupports() throws IOException {
        Set<String> versions = new TreeSet<>();
        try (Stream<Path> files = Files.list(VANILLA)) {
            files.forEach(f -> versions.add(VanillaItems.versionOf(f.getFileName().toString())));
        }
        assertEquals(Set.of("26.2", "26.3"), versions, "pack.mcmeta covers 26.2 (88.0) to 26.3 (97.1)");
        VanillaItems v262 = table("26.2");
        VanillaItems v263 = table("26.3");
        assertEquals("26.2", v262.version());
        assertEquals("26.3", v263.version());
        assertEquals(1537, v262.size(), "every assets/minecraft/items/*.json of the 26.2 client jar");
        assertEquals(1658, v263.size(), "every assets/minecraft/items/*.json of the 26.3 client jar");
    }

    @Test
    void kindsAndParentsComeFromTheVanillaModels() throws IOException {
        VanillaItems vanilla = table("26.2");
        assertEquals(VanillaItems.Kind.BLOCK, vanilla.kind("cobblestone"));
        assertEquals(VanillaItems.Kind.BLOCK, vanilla.kind("crafting_table"));
        assertEquals(VanillaItems.Kind.ITEM, vanilla.kind("dried_kelp"));
        assertEquals(VanillaItems.Kind.OTHER, vanilla.kind("compass"));
        assertEquals(VanillaItems.Kind.OTHER, vanilla.kind("leather_helmet"), "tinted, so not a plain model");
        assertNull(vanilla.kind("no_such_item"));
        assertEquals("minecraft:item/handheld", vanilla.itemParent("diamond_sword"));
        assertEquals("minecraft:item/handheld_rod", vanilla.itemParent("carrot_on_a_stick"));
        assertEquals(VanillaItems.GENERATED, vanilla.itemParent("big_dripleaf"), "a block parent is not a template");
        assertEquals(VanillaItems.GENERATED, vanilla.itemParent("cobblestone"));
        assertEquals(VanillaItems.GENERATED, vanilla.itemParent("no_such_item"));
    }

    @Test
    void aDefinitionIsACopy() throws IOException {
        VanillaItems vanilla = table("26.2");
        var first = vanilla.definition("dried_kelp");
        first.remove("model");
        assertTrue(vanilla.definition("dried_kelp").has("model"));
        assertNotSame(vanilla.definition("paper"), vanilla.definition("paper"));
        assertNull(vanilla.definition("no_such_item"));
    }

    @Test
    void theTableIsPickedByVersionNeverGuessed() {
        List<String> available = List.of("26.2", "26.3");
        assertEquals("26.2", VanillaItems.pick("26.2", available));
        assertEquals("26.3", VanillaItems.pick("26.3", available));
        assertEquals("26.3", VanillaItems.pick("26.3.1", available), "a patch release uses its minor's table");
        assertNull(VanillaItems.pick("26.4", available), "a newer Minecraft gets no art rather than old fallbacks");
        assertNull(VanillaItems.pick("26.1.2", available));
        assertNull(VanillaItems.pick(null, available));
        assertEquals("26.3", VanillaItems.versionOf("items-26.3.json"));
        assertNull(VanillaItems.versionOf("README.md"));
        assertNull(VanillaItems.versionOf("items-.json"));
    }

    @Test
    void theJavaPackItselfCarriesNoVanillaFiles() throws IOException {
        assertFalse(PackFiles.fromDirectory(Path.of("src/main/resources/resourcepack/java")).keySet().stream()
                .anyMatch(p -> p.startsWith("vanilla/") || p.startsWith("items-")));
    }
}
