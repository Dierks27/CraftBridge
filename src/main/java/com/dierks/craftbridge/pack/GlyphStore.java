package com.dierks.craftbridge.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which character each glyph key has, kept in {@code plugins/CraftBridge/pack/glyphs.yml} so a
 * key keeps its character across rebuilds, restarts and updates. A key is given the lowest free
 * character from {@link Glyphs#FIRST} the first time anyone asks for it, and the file is written
 * at once. Entries are never removed by CraftBridge: a key whose art went away keeps its
 * character, so text already written with it (a LuckPerms prefix, a sign) is not re-used for
 * something else.
 *
 * <p>The file is plain YAML, one {@code "key": "U+E000"} per line under {@code glyphs:}. When it
 * cannot be read the store keeps working in memory but never writes over it, the way the other
 * stores treat a hand-edited file with a mistake in it.
 */
public final class GlyphStore {

    private static final Pattern LINE = Pattern.compile(
            "\\s*['\"]?([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]?\\s*:\\s*['\"]?U\\+([0-9A-Fa-f]{4,6})['\"]?\\s*(#.*)?");

    private static final String HEADER = """
            # Glyph characters, assigned by CraftBridge. A key keeps its character forever, so
            # text written with it (titles, lore, chat, LuckPerms prefixes) never changes meaning.
            # Do not change a character that is in use. Deleting a line frees that character
            # for the next new key. /craftbridge pack glyphs lists them all.
            glyphs:
            """;

    private final Path file;
    private final Logger log;
    private final SortedMap<String, Integer> byKey = new TreeMap<>();
    private final Map<Integer, String> byCode = new HashMap<>();
    /** False when the file was there but could not be read: never save over it. */
    private final boolean writable;
    private boolean warnedReadOnly;

    private GlyphStore(Path file, Logger log, boolean writable) {
        this.file = file;
        this.log = log;
        this.writable = writable;
    }

    /** Load the file (none yet is fine); a line it cannot read is reported and the file left alone from then on. */
    public static GlyphStore load(Path file, Logger log) {
        if (!Files.exists(file)) {
            return new GlyphStore(file, log, true);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.severe("Pack: " + file.getFileName() + " could not be read (" + ex.getMessage() + "); glyphs get"
                    + " characters for this session only and the file is not written. Fix it and restart.");
            return new GlyphStore(file, log, false);
        }
        List<String> bad = new ArrayList<>();
        GlyphStore store = new GlyphStore(file, log, true);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("glyphs:")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                bad.add("line " + (i + 1) + ": " + trimmed);
                continue;
            }
            String key = m.group(1);
            int code = Integer.parseInt(m.group(2), 16);
            if (code < Glyphs.FIRST || code > Glyphs.LAST) {
                bad.add("line " + (i + 1) + ": " + Glyphs.hex(code) + " is outside " + Glyphs.hex(Glyphs.FIRST)
                        + " to " + Glyphs.hex(Glyphs.LAST));
                continue;
            }
            if (store.byKey.containsKey(key)) {
                bad.add("line " + (i + 1) + ": " + key + " is listed twice");
                continue;
            }
            String other = store.byCode.get(code);
            if (other != null) {
                bad.add("line " + (i + 1) + ": " + Glyphs.hex(code) + " is already " + other);
                continue;
            }
            store.byKey.put(key, code);
            store.byCode.put(code, key);
        }
        if (!bad.isEmpty()) {
            log.severe("Pack: " + file.getFileName() + " has lines CraftBridge cannot use, so it will not write"
                    + " over the file this session (new glyphs get characters in memory only): "
                    + String.join("; ", bad) + ". Fix them and restart.");
            GlyphStore readOnly = new GlyphStore(file, log, false);
            readOnly.byKey.putAll(store.byKey);
            readOnly.byCode.putAll(store.byCode);
            return readOnly;
        }
        return store;
    }

    /** The character of a key, given a new one (and saved) the first time; -1 when every character is taken. */
    public synchronized int codepoint(String key) {
        Glyphs.requireKey(key);
        Integer known = byKey.get(key);
        if (known != null) {
            return known;
        }
        Glyphs.Space space = Glyphs.SPACES.get(key);
        if (space != null) {
            return space.codepoint();
        }
        int code = Glyphs.FIRST;
        while (code <= Glyphs.LAST && byCode.containsKey(code)) {
            code++;
        }
        if (code > Glyphs.LAST) {
            log.severe("Pack: every glyph character is taken; " + key + " gets none. Remove unused lines from "
                    + file.getFileName() + ".");
            return -1;
        }
        byKey.put(key, code);
        byCode.put(code, key);
        save();
        return code;
    }

    /** The character of a key without assigning one: null when it has none yet. */
    public synchronized Integer peek(String key) {
        Glyphs.Space space = Glyphs.SPACES.get(key);
        return space != null ? Integer.valueOf(space.codepoint()) : byKey.get(key);
    }

    /** Every assigned key and its character (the fixed spaces not included). */
    public synchronized SortedMap<String, Integer> all() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(byKey));
    }

    private void save() {
        if (!writable) {
            if (!warnedReadOnly) {
                warnedReadOnly = true;
                SafeLog.refuse(log, file);
            }
            return;
        }
        StringBuilder text = new StringBuilder(HEADER);
        for (Map.Entry<String, Integer> e : byKey.entrySet()) {
            text.append("  \"").append(e.getKey()).append("\": \"").append(Glyphs.hex(e.getValue())).append("\"\n");
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.severe("Pack: could not write " + file + " (" + ex.getMessage() + "); new glyph characters are"
                    + " kept in memory only until the next restart.");
        }
    }

    private static final class SafeLog {
        static void refuse(Logger log, Path file) {
            log.severe("Pack: not saving " + file.getFileName() + ": it had lines CraftBridge could not read at"
                    + " startup. New glyph characters are in memory only; fix the file and restart.");
        }
    }
}
