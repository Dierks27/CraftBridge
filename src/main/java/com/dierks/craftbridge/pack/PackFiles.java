package com.dierks.craftbridge.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The files of a resource pack, as a sorted map of pack-relative path to bytes, and the zip
 * built from them. No Bukkit here, so the packs can be built and checked in tests.
 *
 * <p>The zip is deterministic: entries in path order, every timestamp the same fixed date and
 * the same compression. The same files therefore always give the same bytes and the same
 * SHA-1, so a client that already downloaded the pack keeps using its cached copy after a
 * restart, and an uploaded copy stays valid until the pack really changes.
 */
public final class PackFiles {

    /** The Java pack's id on the client: fixed, so a new version replaces the old one instead of stacking. */
    public static final java.util.UUID JAVA_PACK_ID = java.util.UUID.fromString("1759558b-8b5b-4c79-8136-9b2b335aaa95");

    /** In the jar: {@code resourcepack/java/}, {@code resourcepack/bedrock/}, {@code resourcepack/geyser/}. */
    public static final String JAR_ROOT = "resourcepack/";

    /** Every zip entry carries this time (DOS time has no time zone, so it is the same everywhere). */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** Files skipped wherever they are found: editor and OS litter. */
    private static boolean ignored(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() || name.equals(".DS_Store") || name.equals("Thumbs.db") || name.startsWith("._")
                || name.endsWith(".bbmodel") || name.endsWith("~");
    }

    private PackFiles() {
    }

    /**
     * Every file under {@code prefix} in a jar (or zip), keyed by its path below the prefix.
     *
     * @param prefix e.g. {@code resourcepack/java/}, with the trailing slash
     */
    public static SortedMap<String, byte[]> fromJar(Path jar, String prefix) throws IOException {
        SortedMap<String, byte[]> out = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || name.length() == prefix.length()) {
                    continue;
                }
                String path = name.substring(prefix.length());
                if (ignored(path)) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    out.put(path, in.readAllBytes());
                }
            }
        }
        return out;
    }

    /** Every file below a directory, keyed by its path relative to it with forward slashes; empty when it does not exist. */
    public static SortedMap<String, byte[]> fromDirectory(Path dir) throws IOException {
        SortedMap<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String path = dir.relativize(file).toString().replace('\\', '/');
                if (!ignored(path)) {
                    out.put(path, Files.readAllBytes(file));
                }
            }
        }
        return out;
    }

    /** {@code base} with every file of {@code overrides} added or replacing the one at the same path. */
    public static SortedMap<String, byte[]> overlay(Map<String, byte[]> base, Map<String, byte[]> overrides) {
        SortedMap<String, byte[]> out = new TreeMap<>(base);
        out.putAll(overrides);
        return out;
    }

    /** The files as a deterministic zip. */
    public static byte[] zip(Map<String, byte[]> files) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("writing a zip in memory failed", ex);
        }
        return bytes.toByteArray();
    }

    /** Lower-case hex SHA-1, the form Minecraft's resource pack hash takes. */
    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("the JDK has no SHA-1", ex);
        }
    }
}
