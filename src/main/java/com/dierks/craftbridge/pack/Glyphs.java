package com.dierks.craftbridge.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Glyph keys, the fixed space characters and the {@code glyphs.json} a source declares its glyphs
 * in. No Bukkit here.
 *
 * <p>Glyphs get characters from the Private Use Area: {@link #FIRST} to {@link #LAST} are handed
 * out by key ({@link GlyphStore}); U+F800 to U+F8FF is kept for the fixed space characters, which
 * follow the long-standing "negative space font" layout (U+F801 = -1 pixel, U+F821 = +1 pixel),
 * so text written for that layout works here too.
 */
public final class Glyphs {

    /** The first character handed out to a glyph key. */
    public static final int FIRST = 0xE000;
    /** The last character handed out; U+F800 and up are the fixed spaces. */
    public static final int LAST = 0xF7FF;

    /** {@code namespace:name}, the way Minecraft spells a resource location, always with the namespace. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** The file a source declares its glyphs in, at its root. */
    public static final String FILE = "glyphs.json";

    /** Default size of a glyph: the height of a line of text, sitting on the baseline like a capital letter. */
    public static final int DEFAULT_HEIGHT = 8;
    public static final int DEFAULT_ASCENT = 7;

    /** The fixed space glyphs, key to (character, pixels), smallest move first within each direction. */
    public static final Map<String, Space> SPACES;

    /** One fixed space glyph. */
    public record Space(String key, int codepoint, int pixels) {
    }

    static {
        Map<String, Space> spaces = new LinkedHashMap<>();
        int[] steps = {1, 2, 3, 4, 5, 6, 7, 8, 16, 32, 64, 128, 256, 512, 1024};
        for (int sign : new int[]{-1, 1}) {
            int base = sign < 0 ? 0xF801 : 0xF821;
            for (int i = 0; i < steps.length; i++) {
                int pixels = sign * steps[i];
                String key = spaceKey(pixels);
                spaces.put(key, new Space(key, base + i, pixels));
            }
        }
        SPACES = Collections.unmodifiableMap(spaces);
    }

    private Glyphs() {
    }

    /** {@code craftbridge:space/-8}, {@code craftbridge:space/64}. */
    public static String spaceKey(int pixels) {
        return "craftbridge:space/" + pixels;
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** The key, or an exception saying what a key must look like. */
    public static String requireKey(String key) {
        if (!validKey(key)) {
            throw new IllegalArgumentException("'" + key + "' is not a glyph key: use namespace:name in lower case,"
                    + " e.g. hcm:coin");
        }
        return key;
    }

    public static String text(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    /** {@code U+E000}. */
    public static String hex(int codepoint) {
        return String.format("U+%04X", codepoint);
    }

    /**
     * The fixed space characters that together move the text by {@code pixels}, biggest steps
     * first; empty for 0. Any whole number of pixels can be made.
     */
    public static String space(int pixels) {
        StringBuilder out = new StringBuilder();
        int sign = pixels < 0 ? -1 : 1;
        long left = Math.abs((long) pixels);
        List<Space> steps = new ArrayList<>();
        for (Space s : SPACES.values()) {
            if (Integer.signum(s.pixels()) == sign) {
                steps.add(0, s); // biggest first
            }
        }
        for (Space s : steps) {
            int size = Math.abs(s.pixels());
            while (left >= size) {
                out.appendCodePoint(s.codepoint());
                left -= size;
            }
        }
        return out.toString();
    }

    /**
     * A glyph a source asks for in its {@code glyphs.json}.
     *
     * @param texture the PNG as a resource location below {@code textures/}, with {@code .png}, e.g.
     *                {@code hcm:font/coin.png}, the way a font's {@code bitmap} provider names it
     */
    public record Declared(String key, String texture, int height, int ascent) {
    }

    /** What a {@code glyphs.json} held: the glyphs, and a line for each entry left out. */
    public record Declarations(List<Declared> glyphs, List<String> problems) {
    }

    /**
     * Read a {@code glyphs.json}:
     * <pre>{@code
     * { "glyphs": { "hcm:coin": { "texture": "hcm:font/coin.png", "height": 8, "ascent": 7 } } }
     * }</pre>
     * {@code height} and {@code ascent} are optional (8 and 7); a bare string is the texture alone.
     */
    public static Declarations parse(JsonObject root) {
        List<Declared> glyphs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        JsonElement section = root.get("glyphs");
        if (section == null || !section.isJsonObject()) {
            problems.add("it has no \"glyphs\" object");
            return new Declarations(glyphs, problems);
        }
        for (Map.Entry<String, JsonElement> entry : section.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (!validKey(key)) {
                problems.add("'" + key + "' is not a glyph key (namespace:name in lower case)");
                continue;
            }
            JsonElement value = entry.getValue();
            String texture;
            int height = DEFAULT_HEIGHT;
            int ascent = DEFAULT_ASCENT;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                texture = value.getAsString();
            } else if (value.isJsonObject()) {
                JsonObject glyph = value.getAsJsonObject();
                JsonElement t = glyph.get("texture");
                texture = t != null && t.isJsonPrimitive() ? t.getAsString() : null;
                try {
                    height = intOr(glyph, "height", DEFAULT_HEIGHT);
                    ascent = intOr(glyph, "ascent", Math.min(DEFAULT_ASCENT, height));
                } catch (IllegalArgumentException ex) {
                    problems.add(key + ": " + ex.getMessage());
                    continue;
                }
            } else {
                problems.add(key + ": expected {\"texture\": ...} or a texture name");
                continue;
            }
            if (texture == null || texture.isBlank()) {
                problems.add(key + ": no \"texture\"");
                continue;
            }
            texture = normalizeTexture(texture.trim());
            if (texture == null) {
                problems.add(key + ": the texture must be namespace:path in lower case, e.g. hcm:font/coin.png");
                continue;
            }
            if (height < 1 || height > 256) {
                problems.add(key + ": height " + height + " is outside 1 to 256");
                continue;
            }
            if (ascent > height) {
                // The client throws the whole provider away when ascent > height.
                problems.add(key + ": ascent " + ascent + " is more than its height " + height);
                continue;
            }
            glyphs.add(new Declared(key, texture, height, ascent));
        }
        return new Declarations(glyphs, problems);
    }

    /** {@code hcm:font/coin} or {@code hcm:font/coin.png} to {@code hcm:font/coin.png}; null when it is no resource location. */
    static String normalizeTexture(String texture) {
        String name = texture.contains(":") ? texture : "minecraft:" + texture;
        if (!name.endsWith(".png")) {
            name += ".png";
        }
        return KEY.matcher(name).matches() ? name : null;
    }

    /** The pack path of a {@code namespace:path} below {@code textures/}. */
    static String texturePath(String location) {
        int colon = location.indexOf(':');
        return "assets/" + location.substring(0, colon) + "/textures/" + location.substring(colon + 1);
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a whole number");
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a whole number");
        }
        return (int) number;
    }
}
