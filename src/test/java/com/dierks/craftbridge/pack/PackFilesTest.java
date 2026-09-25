package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack zip is what clients cache and admins upload, so it must be byte-for-byte stable. */
class PackFilesTest {

    static final Path JAVA_PACK = Path.of("src/main/resources/resourcepack/java");

    @TempDir
    Path dir;

    @Test
    void theSameFilesAlwaysGiveTheSameZipAndHash() throws IOException {
        byte[] first = PackFiles.zip(PackFiles.fromDirectory(JAVA_PACK));
        byte[] second = PackFiles.zip(PackFiles.fromDirectory(JAVA_PACK));
        assertArrayEquals(first, second);
        assertEquals(PackFiles.sha1(first), PackFiles.sha1(second));
        assertEquals(40, PackFiles.sha1(first).length());
        assertTrue(PackFiles.sha1(first).matches("[0-9a-f]{40}"), "lower-case hex, as the client expects");
    }

    @Test
    void theZipHoldsEveryFileInPathOrderAtTheRoot() throws IOException {
        SortedMap<String, byte[]> files = PackFiles.fromDirectory(JAVA_PACK);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(PackFiles.zip(files)))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
                assertArrayEquals(files.get(e.getName()), zip.readAllBytes(), e.getName());
            }
        }
        assertEquals(new ArrayList<>(files.keySet()), names);
        assertTrue(names.contains("pack.mcmeta"), "pack.mcmeta at the root: " + names);
        assertTrue(names.contains("assets/minecraft/items/crafting_table.json"));
        assertTrue(names.stream().noneMatch(n -> n.startsWith("/") || n.contains("\\")), names.toString());
    }

    @Test
    void anOverrideReplacesAFileAndAddsNewOnes() throws IOException {
        Path overrides = dir.resolve("overrides");
        Files.createDirectories(overrides.resolve("assets/craftbridge/textures/block"));
        Files.writeString(overrides.resolve("pack.mcmeta"), "{}");
        Files.writeString(overrides.resolve("assets/craftbridge/textures/block/extra.png"), "png");
        Files.writeString(overrides.resolve(".DS_Store"), "litter");
        Files.writeString(overrides.resolve("workbench.bbmodel"), "blockbench project");

        SortedMap<String, byte[]> base = PackFiles.fromDirectory(JAVA_PACK);
        SortedMap<String, byte[]> merged = PackFiles.overlay(base, PackFiles.fromDirectory(overrides));

        assertEquals("{}", new String(merged.get("pack.mcmeta"), StandardCharsets.UTF_8));
        assertTrue(merged.containsKey("assets/craftbridge/textures/block/extra.png"));
        assertFalse(merged.containsKey(".DS_Store"));
        assertFalse(merged.containsKey("workbench.bbmodel"), "Blockbench projects stay out of the pack");
        assertEquals(base.size() + 1, merged.size());
        assertNotEquals(PackFiles.sha1(PackFiles.zip(base)), PackFiles.sha1(PackFiles.zip(merged)));
    }

    @Test
    void aMissingOverridesFolderIsNoOverrides() throws IOException {
        assertTrue(PackFiles.fromDirectory(dir.resolve("nope")).isEmpty());
    }

    @Test
    void theJarReaderTakesOnlyItsPrefixWithThePrefixCut() throws IOException {
        Path jar = dir.resolve("CraftBridge.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            for (Map.Entry<String, String> e : Map.of(
                    "resourcepack/java/pack.mcmeta", "meta",
                    "resourcepack/java/assets/x.json", "x",
                    "resourcepack/bedrock/manifest.json", "bedrock",
                    "config.yml", "config").entrySet()) {
                zip.putNextEntry(new JarEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new JarEntry("resourcepack/java/assets/"));
            zip.closeEntry();
        }

        SortedMap<String, byte[]> files = PackFiles.fromJar(jar, PackFiles.JAR_ROOT + "java/");

        assertEquals(List.of("assets/x.json", "pack.mcmeta"), new ArrayList<>(files.keySet()));
        assertEquals("meta", new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8));
    }

    @Test
    void theSha1IsTheStandardOne() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PackFiles.sha1("abc".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PackFiles.sha1(PackFiles.zip(new TreeMap<>())), PackFiles.sha1(PackFiles.zip(Map.of())));
    }
}
