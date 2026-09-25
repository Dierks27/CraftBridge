package com.dierks.craftbridge.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an admin's {@code config.yml} up to the shape this jar expects, once per upgrade, so
 * updating CraftBridge is "drop in the jar" and nothing more.
 *
 * <p>Bukkit never writes new keys into an existing config.yml. Plain settings do not suffer
 * for it, because {@code getConfig()} falls back to the jar's copy in memory — but lists and
 * sections do. A list the admin already has hides the jar's longer one completely (so new
 * {@code jei.recipe-sync.types} never arrive), and {@code getConfigurationSection} on a
 * section that exists only in the jar hands back a <em>new, empty</em> section rather than
 * the jar's (so an old config reads {@code combo-chest.recipe.ingredients} as nothing, which
 * is the "row 1 uses 'H', which has no entry under ingredients" warning).
 *
 * <p>A file without {@link #VERSION_KEY} counts as version 0. When the file is older than
 * {@link #CURRENT_VERSION} this, in order:
 * <ol>
 *   <li>copies the file byte for byte to {@code config.yml.bak-v<old>} (once — an existing
 *       backup is the true original and is never overwritten);</li>
 *   <li>adds every setting the jar has and the file does not, with the jar's comments. A
 *       value the admin set is never changed;</li>
 *   <li>repairs a recipe block whose ingredient map is empty or missing while its shape is
 *       still the jar's, by filling in the jar's ingredients;</li>
 *   <li>runs the versioned steps for the versions in between — the only way entries are
 *       ever added to a list the admin already has, so an entry they deliberately removed
 *       comes back at most once, on the upgrade that introduced it, and never again;</li>
 *   <li>stamps the new version and saves, keeping the file's comments.</li>
 * </ol>
 * When the upgrade only appends list entries and stamps the version — every upgrade from a
 * 0.13 file — those lines are inserted into the admin's text as it is, and every other byte
 * stays put: their layout, flow-style lists and comments inside lists included. The edited
 * text is parsed back and must read exactly like the full migration, or the full save is used.
 * Anything more (whole sections added to a much older file) goes through Paper's YAML writer,
 * which keeps comments on settings but lays some things out anew.
 * A file at the current version is not touched at all, so a second boot changes nothing.
 *
 * <p>Lists and maps whose contents the admin chooses ({@link #WHOLE_VALUES}) are treated as
 * one value: added when missing, never merged entry by entry. Merging the jar's ingredient
 * letters into a customised recipe, or its sort categories into an admin's own, would only
 * corrupt them.
 *
 * <p>This works on its own copy of the file with <em>no defaults attached</em>. With the
 * jar's defaults attached, as on {@code getConfig()}, a missing key reads as present:
 * {@code getInt("config-version")} returns the jar's number for a file that has none.
 */
public final class ConfigMigrator {

    public static final String VERSION_KEY = "config-version";

    /**
     * Bump whenever the bundled config.yml changes: a new setting as much as a new list entry.
     * A file already at this version is never looked at again, so a setting added without a
     * bump would never reach existing servers. ConfigMigratorTest holds the bundled keys for
     * this version and fails until the number is bumped.
     */
    public static final int CURRENT_VERSION = 2;

    /** Values the admin owns as a whole: added when missing, never merged entry by entry. */
    private static final Set<String> WHOLE_VALUES = Set.of(
            "sorting.categories",
            "jei.recipe-sync.types",
            "linked-workbench.recipe.shape", "linked-workbench.recipe.ingredients",
            "combo-chest.recipe.shape", "combo-chest.recipe.ingredients");

    /**
     * Settings whose absence means something the jar's value does not, so filling them in
     * would change what the server does. A missing {@code linked-workbench.head-texture} reads
     * as "" (show display-item); the jar's value is a textured head.
     */
    private static final Set<String> NEVER_ADD = Set.of("linked-workbench.head-texture");

    /** Blocks whose {@code recipe.shape} and {@code recipe.ingredients} must agree with each other. */
    private static final List<String> RECIPE_BLOCKS = List.of("linked-workbench", "combo-chest");

    /**
     * One upgrade step: applied to a file older than {@code version}. Add one (with the bump
     * of {@link #CURRENT_VERSION} it comes with) whenever a release adds entries to a list the
     * admin may already have; missing settings need no step, they are added for any older file.
     */
    private record Step(int version, String list, List<String> entries) {
    }

    private static final List<Step> STEPS = List.of(
            // 0.13.1: Minecraft 26.3 made brewing a recipe type, and JEI 26.3 reads its
            // Brewing category from the server's synced recipes.
            new Step(2, "jei.recipe-sync.types", List.of("minecraft:brewing")));

    /**
     * What a run did.
     *
     * @param from    the file's version before the run (0 when it had none)
     * @param to      its version after the run; equal to {@code from} when nothing was done
     * @param changes one entry per change, in the words the log line uses
     * @param backup  the backup of the original file, or null when nothing was saved
     */
    public record Result(int from, int to, List<String> changes, Path backup) {
        public boolean migrated() {
            return to != from;
        }
    }

    private ConfigMigrator() {
    }

    /**
     * Migrate {@code file} against the jar's bundled config.
     *
     * @param file    the admin's config.yml; nothing happens when it does not exist yet
     *                (saveDefaultConfig writes the jar's, which is already current)
     * @param bundled the jar's config.yml
     * @param log     where the one-line summary (or the reason nothing was done) goes
     */
    public static Result migrate(Path file, Reader bundled, Logger log) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Result(CURRENT_VERSION, CURRENT_VERSION, List.of(), null);
        }
        String bundledText = readAll(bundled);
        YamlConfiguration defaults = load(bundledText);
        String original;
        try {
            original = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            log.warning(file.getFileName() + " is not UTF-8 text, so it was not migrated; save it as UTF-8"
                    + " and restart to let CraftBridge upgrade it.");
            return new Result(0, 0, List.of(), null);
        }
        YamlConfiguration live = new YamlConfiguration();
        live.options().parseComments(true);
        try {
            live.loadFromString(original);
        } catch (InvalidConfigurationException | RuntimeException ex) {
            // loadConfiguration would have swallowed this and returned an empty config, and
            // saving that would write bare defaults over the admin's file. Leave it alone.
            log.severe(file.getFileName() + " is not valid YAML, so it was not migrated: " + firstLine(ex));
            return new Result(0, 0, List.of(), null);
        }

        int from = versionOf(live);
        if (from >= CURRENT_VERSION) {
            if (from > CURRENT_VERSION) {
                log.warning(file.getFileName() + " says " + VERSION_KEY + ": " + from + ", newer than this jar"
                        + " understands (" + CURRENT_VERSION + "); it was left as it is.");
            }
            return new Result(from, from, List.of(), null);
        }

        List<String> added = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        addMissing(defaults, live, added);
        List<String> repaired = repairRecipes(defaults, live, added);
        if (!added.isEmpty()) {
            changes.add("added " + String.join(", ", added));
        }
        Map<String, List<String>> appendedTo = new LinkedHashMap<>();
        for (Step step : STEPS) {
            if (from < step.version()) {
                List<String> appended = appendToList(live, step.list(), step.entries());
                if (!appended.isEmpty()) {
                    changes.add("added " + step.list() + " " + appended);
                    appendedTo.computeIfAbsent(step.list(), k -> new ArrayList<>()).addAll(appended);
                }
            }
        }
        for (String path : repaired) {
            changes.add("repaired " + path);
        }

        live.set(VERSION_KEY, CURRENT_VERSION);
        if (live.getComments(VERSION_KEY).isEmpty()) {
            live.setComments(VERSION_KEY, defaults.getComments(VERSION_KEY));
        }

        Path backup = file.resolveSibling(file.getFileName() + ".bak-v" + from);
        boolean keptOlderBackup = Files.exists(backup);
        if (!keptOlderBackup) {
            // Copied beside it first and moved into place, so a crash mid-copy can never leave
            // a partial backup that a later boot would then keep as "the original".
            Path partial = backup.resolveSibling(backup.getFileName() + ".tmp");
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
            move(partial, backup);
        }
        String edited = null;
        if (added.isEmpty() && repaired.isEmpty()) {
            try {
                String candidate = editInPlace(original, appendedTo, versionBlock(bundledText));
                edited = candidate != null && readsLike(candidate, live) ? candidate : null;
            } catch (RuntimeException | StackOverflowError unexpectedLayout) {
                edited = null; // the YAML writer handles whatever the hand edit could not
            }
        }
        write(file, edited != null ? edited : live.saveToString());

        String summary = "Config migrated v" + from + " → v" + CURRENT_VERSION + ": "
                + (changes.isEmpty() ? "nothing to add" : String.join("; ", changes))
                + "; " + (keptOlderBackup ? "kept the existing backup " : "the old file is ")
                + backup.getFileName();
        log.info(summary);
        return new Result(from, CURRENT_VERSION, List.copyOf(changes), backup);
    }

    // ---- the steps -------------------------------------------------------------------

    /**
     * Every path the jar has and the file does not, copied with its comments. Walked parent
     * first, and a missing section is copied whole, so {@code added} names the highest path
     * that was missing rather than every leaf under it.
     */
    private static void addMissing(YamlConfiguration defaults, YamlConfiguration live, List<String> added) {
        for (String path : defaults.getKeys(true)) {
            if (path.equals(VERSION_KEY) || NEVER_ADD.contains(path) || isInsideWholeValue(path) || isRecipePair(path)) {
                continue;
            }
            if (live.contains(path, true) || underAScalar(live, path)) {
                continue;
            }
            copy(defaults, live, path);
            added.add(path);
        }
    }

    /**
     * A recipe block whose ingredient map is missing or empty while its shape is missing or
     * uses only the jar's letters gets the jar's ingredients (and shape, when missing). A customised pair is left to
     * {@code ShapeSpec} to judge: filling in the jar's letters would not make it valid.
     *
     * @return the paths repaired; a shape that was simply missing goes to {@code added}
     */
    private static List<String> repairRecipes(YamlConfiguration defaults, YamlConfiguration live, List<String> added) {
        List<String> repaired = new ArrayList<>();
        for (String block : RECIPE_BLOCKS) {
            String recipe = block + ".recipe";
            if (!(live.get(recipe, null) instanceof ConfigurationSection)) {
                continue; // missing entirely (addMissing copied the jar's) or not a section
            }
            String shapePath = recipe + ".shape";
            String ingredientsPath = recipe + ".ingredients";
            Object shape = live.get(shapePath, null);
            Object ingredients = live.get(ingredientsPath, null);
            List<String> defaultShape = defaults.getStringList(shapePath);

            boolean noIngredients = ingredients == null
                    || ingredients instanceof ConfigurationSection section && section.getKeys(false).isEmpty();
            // The jar's shape, or one drawn only with the jar's letters: either way the jar's
            // ingredients are what it needs.
            boolean jarShape = shape == null || shape instanceof List<?> rows && !rows.isEmpty()
                    && letters(defaultShape).containsAll(letters(asStrings(rows)));

            if (noIngredients && jarShape && defaults.contains(ingredientsPath, true)) {
                List<String> comments = ingredients == null ? List.of() : live.getComments(ingredientsPath);
                if (ingredients != null) {
                    live.set(ingredientsPath, null);
                }
                copy(defaults, live, ingredientsPath);
                if (!comments.isEmpty()) {
                    live.setComments(ingredientsPath, comments);
                }
                repaired.add(ingredientsPath);
                if (shape == null) {
                    copy(defaults, live, shapePath);
                    added.add(shapePath);
                }
            } else if (shape == null && ingredients instanceof ConfigurationSection section
                    && section.getKeys(false).containsAll(letters(defaultShape))) {
                // The admin's own ingredients already name every letter the jar's shape uses.
                copy(defaults, live, shapePath);
                added.add(shapePath);
            }
        }
        return repaired;
    }

    /**
     * Append the entries the list does not have yet. A list that is missing or empty is left
     * alone: missing means addMissing just copied the jar's (which has them), and an empty
     * list is read as "all the built-ins", which appending would narrow.
     */
    private static List<String> appendToList(YamlConfiguration live, String path, List<String> entries) {
        if (!(live.get(path, null) instanceof List<?> current) || current.isEmpty()) {
            return List.of();
        }
        List<Object> updated = new ArrayList<>(current);
        List<String> appended = new ArrayList<>();
        for (String entry : entries) {
            boolean present = current.stream().anyMatch(e -> e != null && String.valueOf(e).trim().equalsIgnoreCase(entry));
            if (!present) {
                updated.add(entry);
                appended.add(entry);
            }
        }
        if (!appended.isEmpty()) {
            live.set(path, updated); // set() on an existing key keeps its comments
        }
        return appended;
    }

    // ---- helpers ---------------------------------------------------------------------

    private static YamlConfiguration load(String bundledText) {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try {
            defaults.loadFromString(bundledText);
        } catch (InvalidConfigurationException ex) {
            throw new IllegalStateException("the jar's own config.yml does not parse", ex);
        }
        return defaults;
    }

    private static String readAll(Reader reader) throws IOException {
        StringWriter out = new StringWriter();
        reader.transferTo(out);
        return out.toString();
    }

    private static int versionOf(YamlConfiguration live) {
        Object value = live.get(VERSION_KEY, null);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Below one of the {@link #WHOLE_VALUES}: copied with it or not at all. */
    private static boolean isInsideWholeValue(String path) {
        for (String whole : WHOLE_VALUES) {
            if (path.startsWith(whole + ".")) {
                return true;
            }
        }
        return false;
    }

    /** A recipe's shape or ingredients, which {@link #repairRecipes} handles as a pair. */
    private static boolean isRecipePair(String path) {
        for (String block : RECIPE_BLOCKS) {
            if (path.equals(block + ".recipe.shape") || path.equals(block + ".recipe.ingredients")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Something the admin put where the jar has a section, e.g. {@code combo-chest: false}.
     * Setting a path through it would replace their value with a section; leave it be.
     */
    private static boolean underAScalar(YamlConfiguration live, String path) {
        int dot = path.indexOf('.');
        while (dot >= 0) {
            Object ancestor = live.get(path.substring(0, dot), null);
            if (ancestor != null && !(ancestor instanceof ConfigurationSection)) {
                return true;
            }
            dot = path.indexOf('.', dot + 1);
        }
        return false;
    }

    /** Copy one path from the jar's config, sections deeply, with every comment on the way. */
    private static void copy(YamlConfiguration from, YamlConfiguration to, String path) {
        if (from.get(path, null) instanceof ConfigurationSection section) {
            to.createSection(path);
            for (String child : section.getKeys(false)) {
                copy(from, to, path + "." + child);
            }
        } else {
            to.set(path, deepCopy(from.get(path, null)));
        }
        to.setComments(path, from.getComments(path));
        to.setInlineComments(path, from.getInlineComments(path));
    }

    /** Lists and maps out of the jar's config are copied, never shared with the admin's. */
    private static Object deepCopy(Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(deepCopy(element));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, deepCopy(v)));
            return out;
        }
        return value;
    }

    private static List<String> asStrings(List<?> values) {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private static Set<String> letters(List<String> shape) {
        Set<String> out = new java.util.HashSet<>();
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                if (c != ' ') {
                    out.add(String.valueOf(c));
                }
            }
        }
        return out;
    }

    // ---- keeping the admin's text -----------------------------------------------------

    // Greedy up to the colon (the key is trimmed in code): a lazy key with a trailing \s* made
    // a long line with no colon take quadratic time.
    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([^\\s#'\"-][^:#]*|'[^']*'|\"[^\"]*\"):(?:\\s+(.*))?$");
    private static final Pattern ITEM_LINE = Pattern.compile("^(\\s*)-(?:\\s+(.*))?$");
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:./-]*");

    /**
     * The admin's text with the appended list entries inserted after each list's last item
     * and the version stamped, or null when that cannot be done safely by hand (a flow-style
     * or nested list, a map item, mixed line endings, the version key written twice).
     */
    static String editInPlace(String original, Map<String, List<String>> appendedTo, List<String> versionBlock) {
        String bom = original.startsWith("\uFEFF") ? "\uFEFF" : "";
        String body = original.substring(bom.length());
        String eol = "\n";
        if (body.indexOf('\r') >= 0) {
            String rest = body.replace("\r\n", "");
            if (rest.indexOf('\r') >= 0 || rest.indexOf('\n') >= 0) {
                return null; // mixed line endings
            }
            eol = "\r\n";
            body = body.replace("\r\n", "\n");
        }
        List<String> lines = new ArrayList<>(Arrays.asList(body.split("\n", -1)));
        for (Map.Entry<String, List<String>> append : appendedTo.entrySet()) {
            int last = lastItemOfBlockList(lines, append.getKey().split("\\."));
            if (last < 0) {
                return null;
            }
            Matcher item = ITEM_LINE.matcher(lines.get(last));
            if (!item.matches()) {
                return null;
            }
            String indent = item.group(1);
            Matcher prefix = Pattern.compile("^\\s*-\\s+").matcher(lines.get(last));
            String dash = prefix.find() ? prefix.group() : indent + "- ";
            boolean quoted = item.group(2) != null && item.group(2).startsWith("'");
            List<String> inserted = new ArrayList<>();
            for (String entry : append.getValue()) {
                inserted.add(dash + (!quoted && PLAIN_SCALAR.matcher(entry).matches() ? entry : "'" + entry.replace("'", "''") + "'"));
            }
            int at = last + 1;
            while (at < lines.size() && lines.get(at).trim().startsWith("#") && indentOf(lines.get(at)) > indent.length()) {
                at++;
            }
            lines.addAll(at, inserted);
        }

        int versionLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher key = KEY_LINE.matcher(lines.get(i));
            if (key.matches() && key.group(1).isEmpty() && unquote(key.group(2).trim()).equals(VERSION_KEY)) {
                if (versionLine >= 0) {
                    return null; // twice: let the YAML writer sort it out
                }
                versionLine = i;
            }
        }
        if (versionLine >= 0) {
            // Replace the value only: the key as the admin wrote it and any comment after it stay.
            String line = lines.get(versionLine);
            int colon = line.indexOf(':');
            int comment = line.indexOf(" #", colon);
            lines.set(versionLine, line.substring(0, colon + 1) + " " + CURRENT_VERSION
                    + (comment < 0 ? "" : line.substring(comment)));
        } else {
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1); // the "" after the final newline
            }
            int end = lines.size();
            while (end > 0 && lines.get(end - 1).isBlank()) {
                end--;
            }
            if (!versionBlock.isEmpty() && end >= versionBlock.size()
                    && lines.subList(end - versionBlock.size(), end).equals(versionBlock)) {
                lines.add(end, VERSION_KEY + ": " + CURRENT_VERSION);
            } else {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.addAll(versionBlock);
                lines.add(VERSION_KEY + ": " + CURRENT_VERSION);
            }
        }
        String text = String.join("\n", lines);
        text = text.endsWith("\n") ? text : text + "\n";
        return bom + text.replace("\n", eol);
    }

    /**
     * Index of the last {@code - item} line of the block-style list at {@code path}, or -1 when
     * the path is not found or its list is not a plain block list of scalars.
     */
    private static int lastItemOfBlockList(List<String> lines, String[] path) {
        int parentIndent = -1;
        int from = 0;
        int to = lines.size();
        int keyLine = -1;
        int keyIndent = -1;
        for (int depth = 0; depth < path.length; depth++) {
            keyLine = -1;
            int childIndent = -1;
            for (int i = from; i < to; i++) {
                String line = lines.get(i);
                if (isBlankOrComment(line)) {
                    continue;
                }
                int indent = indentOf(line);
                if (indent <= parentIndent) {
                    break;
                }
                if (childIndent < 0) {
                    childIndent = indent;
                }
                Matcher key = KEY_LINE.matcher(line);
                if (indent == childIndent && key.matches() && unquote(key.group(2).trim()).equals(path[depth])) {
                    boolean last = depth == path.length - 1;
                    String value = key.group(3);
                    if (value != null && !value.isBlank() && !value.trim().startsWith("#")) {
                        return -1; // an intermediate with a value, or a flow-style list
                    }
                    keyLine = i;
                    keyIndent = indent;
                    if (!last) {
                        from = i + 1;
                        to = endOfBlock(lines, i, indent);
                        parentIndent = indent;
                    }
                    break;
                }
            }
            if (keyLine < 0) {
                return -1;
            }
        }
        int lastItem = -1;
        for (int i = keyLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) {
                continue;
            }
            int indent = indentOf(line);
            Matcher item = ITEM_LINE.matcher(line);
            if (item.matches() && indent >= keyIndent) {
                String value = item.group(2) == null ? "" : item.group(2).trim();
                if (value.isEmpty() || KEY_LINE.matcher(value).matches() || value.startsWith("[") || value.startsWith("{")) {
                    return -1; // an item that is itself a map or a list
                }
                lastItem = i;
            } else if (indent > keyIndent) {
                return -1; // a continuation line: not a plain list of scalars
            } else {
                break;
            }
        }
        return lastItem;
    }

    private static int endOfBlock(List<String> lines, int keyLine, int indent) {
        for (int i = keyLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isBlankOrComment(line) && indentOf(line) <= indent) {
                return i;
            }
        }
        return lines.size();
    }

    private static boolean isBlankOrComment(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String unquote(String key) {
        if (key.length() >= 2 && (key.startsWith("'") && key.endsWith("'") || key.startsWith("\"") && key.endsWith("\""))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    /** The comment block above {@link #VERSION_KEY} in the jar's own text, blank lines included. */
    private static List<String> versionBlock(String bundledText) {
        List<String> lines = Arrays.asList(bundledText.split("\\r?\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(VERSION_KEY + ":")) {
                int start = i;
                while (start > 0 && lines.get(start - 1).trim().startsWith("#")) {
                    start--;
                }
                return new ArrayList<>(lines.subList(start, i));
            }
        }
        return List.of();
    }

    /** Whether {@code edited} holds exactly the data {@code migrated} does. Comments aside. */
    private static boolean readsLike(String edited, YamlConfiguration migrated) {
        YamlConfiguration check = new YamlConfiguration();
        try {
            check.loadFromString(edited);
        } catch (InvalidConfigurationException ex) {
            return false;
        }
        return plain(check, new int[] {1_000_000}, 0).equals(plain(migrated, new int[] {1_000_000}, 0));
    }

    private static Object plain(Object value, int[] budget, int depth) {
        if (--budget[0] < 0 || depth > 128) {
            throw new IllegalStateException("too large or too deep to compare");
        }
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, plain(section.get(key, null), budget, depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(plain(element, budget, depth + 1));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, plain(v, budget, depth + 1)));
            return out;
        }
        return value;
    }

    /**
     * Replace the file in one step where the file system allows it, so a crash mid-write
     * leaves either the old file or the new one, never half of each.
     */
    private static void write(Path file, String text) throws IOException {
        // Write through a symlinked config.yml to the file it points at, keeping the link.
        Path target = Files.isSymbolicLink(file) ? file.toRealPath() : file;
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            // The new file keeps the old one's permissions, as an in-place save would.
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system (Windows): nothing to carry over
        }
        move(temp, target);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String firstLine(Exception ex) {
        String message = String.valueOf(ex.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
