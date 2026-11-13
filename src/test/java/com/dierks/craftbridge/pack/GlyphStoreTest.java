package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A glyph key keeps its character across restarts; a hand-edited file with a mistake is never overwritten. */
class GlyphStoreTest {

    @TempDir
    Path dir;

    private final List<String> logged = new ArrayList<>();

    private Logger logger() {
        Logger log = Logger.getAnonymousLogger();
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
        return log;
    }

    @Test
    void keysKeepTheirCharacterAcrossARestart() throws IOException {
        Path file = dir.resolve("glyphs.yml");
        GlyphStore store = GlyphStore.load(file, logger());
        assertEquals(0xE000, store.codepoint("hcm:coin"));
        assertEquals(0xE001, store.codepoint("hcm:menu_market_bg"));
        assertEquals(0xE000, store.codepoint("hcm:coin"), "asking again changes nothing");
        assertTrue(Files.readString(file).contains("\"hcm:coin\": \"U+E000\""), Files.readString(file));

        GlyphStore again = GlyphStore.load(file, logger());
        assertEquals(0xE001, again.codepoint("hcm:menu_market_bg"));
        assertEquals(0xE002, again.codepoint("collins:tv"), "a new key gets the next free character");
        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void aFreedCharacterIsReusedAndOthersStayPut() throws IOException {
        Path file = dir.resolve("glyphs.yml");
        Files.writeString(file, "glyphs:\n  \"hcm:b\": \"U+E001\"\n  'hcm:c': U+E002 # hand-written\n");
        GlyphStore store = GlyphStore.load(file, logger());
        assertEquals(0xE000, store.codepoint("hcm:new"));
        assertEquals(0xE002, store.codepoint("hcm:c"));
    }

    @Test
    void aFileWithAMistakeIsNeverWrittenOver() throws IOException {
        Path file = dir.resolve("glyphs.yml");
        String text = "glyphs:\n  \"hcm:coin\": \"U+E000\"\n  \"hcm:oops\" U+E001\n";
        Files.writeString(file, text);
        GlyphStore store = GlyphStore.load(file, logger());
        assertEquals(0xE000, store.codepoint("hcm:coin"));
        assertEquals(0xE001, store.codepoint("hcm:another"), "still works, in memory");
        assertEquals(text, Files.readString(file));
        assertTrue(logged.stream().anyMatch(l -> l.startsWith("SEVERE") && l.contains("line 3")), logged.toString());
    }

    @Test
    void spacesAreFixedAndNeverStored() {
        GlyphStore store = GlyphStore.load(dir.resolve("glyphs.yml"), logger());
        assertEquals(0xF801, store.codepoint("craftbridge:space/-1"));
        assertEquals(0xF82F, store.codepoint("craftbridge:space/1024"));
        assertTrue(store.all().isEmpty());
        assertFalse(Files.exists(dir.resolve("glyphs.yml")));
    }

    @Test
    void aBadKeyIsRefused() {
        GlyphStore store = GlyphStore.load(dir.resolve("glyphs.yml"), logger());
        assertThrows(IllegalArgumentException.class, () -> store.codepoint("Coin"));
        assertThrows(IllegalArgumentException.class, () -> store.codepoint("coin"));
    }

    @Test
    void spaceBuildsAnyWidthFromTheFixedCharacters() {
        assertEquals("", Glyphs.space(0));
        assertEquals("", Glyphs.space(-1));
        assertEquals("", Glyphs.space(-73)); // -64 -8 -1
        assertEquals("", Glyphs.space(2049));
        int total = Glyphs.space(-300).codePoints().map(cp -> Glyphs.SPACES.values().stream()
                .filter(s -> s.codepoint() == cp).findFirst().orElseThrow().pixels()).sum();
        assertEquals(-300, total);
    }

    @Test
    void glyphsJsonDefaultsAndChecks() {
        Glyphs.Declarations read = Glyphs.parse(com.google.gson.JsonParser.parseString(
                "{\"glyphs\":{\"hcm:coin\":\"hcm:font/coin\",\"hcm:tall\":{\"texture\":\"hcm:font/tall.png\",\"height\":32,"
                        + "\"ascent\":40},\"Bad\":\"x\",\"hcm:none\":{}}}").getAsJsonObject());
        assertEquals(1, read.glyphs().size());
        Glyphs.Declared coin = read.glyphs().get(0);
        assertEquals("hcm:font/coin.png", coin.texture());
        assertEquals(8, coin.height());
        assertEquals(7, coin.ascent());
        assertEquals(3, read.problems().size(), read.problems().toString());
    }
}
