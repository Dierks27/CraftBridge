package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(lines.get(lines.size() - 1).contains("copy"), lines.toString());
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
}
