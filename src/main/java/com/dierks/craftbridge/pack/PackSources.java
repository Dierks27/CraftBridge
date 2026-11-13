package com.dierks.craftbridge.pack;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.api.CraftBridgePack;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pack's sources other than CraftBridge's own, and the glyph characters: what other plugins
 * reach through {@link CraftBridgePack} (published in Bukkit's ServicesManager), plus the drop-in
 * folders in {@code plugins/CraftBridge/pack/sources/}.
 *
 * <p>Owned by the plugin, not by the resource-pack feature, so registrations and the glyph store
 * survive {@code /craftbridge reload} (other plugins register once, on their enable).
 */
public final class PackSources implements CraftBridgePack {

    /** The id of CraftBridge's own source: the jar's models, {@code pack/items/} art and {@code pack/overrides/java/}. */
    public static final String OWN = "craftbridge";

    /** Below {@code plugins/CraftBridge/pack/}. */
    public static final String FOLDER = "sources";

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");

    /** A source another plugin registered: a folder on disk, or a folder inside its jar. */
    private record Registered(Plugin owner, String id, Path folder, String jarRoot) {
    }

    private final CraftBridgePlugin plugin;
    private final Map<String, Registered> registered = new LinkedHashMap<>();
    private final GlyphStore glyphs;
    private Runnable rebuild;
    private boolean rebuildQueued;

    public PackSources(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.glyphs = GlyphStore.load(packFolder().resolve("glyphs.yml"), plugin.getLogger());
    }

    private Path packFolder() {
        return plugin.getDataFolder().toPath().resolve("pack");
    }

    public Path dropInFolder() {
        return packFolder().resolve(FOLDER);
    }

    public GlyphStore glyphStore() {
        return glyphs;
    }

    /** What {@link #requestRebuild()} runs; null while there is no built pack to rebuild. */
    public synchronized void rebuildWith(Runnable rebuild) {
        this.rebuild = rebuild;
    }

    // ---- the API ---------------------------------------------------------------------------

    @Override
    public void registerSource(Plugin owner, String sourceId, Path folder) {
        Objects.requireNonNull(folder, "folder");
        register(new Registered(Objects.requireNonNull(owner, "plugin"), checkId(sourceId), folder, null));
    }

    @Override
    public void registerSource(Plugin owner, String sourceId, String jarRoot) {
        Objects.requireNonNull(jarRoot, "jarRoot");
        String root = jarRoot.replace('\\', '/');
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        if (!root.isEmpty() && !root.endsWith("/")) {
            root += "/";
        }
        register(new Registered(Objects.requireNonNull(owner, "plugin"), checkId(sourceId), null, root));
    }

    private static String checkId(String sourceId) {
        if (sourceId == null || !ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("'" + sourceId + "' is not a source id: use lower-case letters,"
                    + " digits, _ . and -, e.g. hcm");
        }
        if (sourceId.equals(OWN)) {
            throw new IllegalArgumentException("'" + OWN + "' is CraftBridge's own source id");
        }
        return sourceId;
    }

    private synchronized void register(Registered source) {
        Registered earlier = registered.get(source.id());
        if (earlier != null && !earlier.owner().getName().equals(source.owner().getName())) {
            throw new IllegalArgumentException("the pack source '" + source.id() + "' is already registered by "
                    + earlier.owner().getName());
        }
        registered.put(source.id(), source);
        plugin.getLogger().info("Pack: " + source.owner().getName() + " added the source '" + source.id() + "' ("
                + (source.folder() != null ? source.folder() : "its jar" + (source.jarRoot().isEmpty() ? "" : ", "
                + source.jarRoot())) + ").");
    }

    @Override
    public String glyph(String key) {
        int code = glyphs.codepoint(key);
        return code < 0 ? "" : Glyphs.text(code);
    }

    @Override
    public String space(int pixels) {
        return Glyphs.space(pixels);
    }

    @Override
    public void requestRebuild() {
        synchronized (this) {
            if (rebuild == null || rebuildQueued) {
                return;
            }
            rebuildQueued = true;
        }
        Runnable run = () -> {
            Runnable target;
            synchronized (this) {
                rebuildQueued = false;
                target = rebuild;
            }
            if (target != null) {
                target.run();
            }
        };
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, run);
        }
    }

    // ---- reading the sources ---------------------------------------------------------------

    /** Every source but CraftBridge's own, read now: the plugins' (while they are enabled) and the drop-ins. */
    public List<PackAssembler.Source> read(List<String> problems) {
        List<PackAssembler.Source> out = new ArrayList<>();
        List<Registered> plugins;
        synchronized (this) {
            plugins = new ArrayList<>(registered.values());
        }
        for (Registered source : plugins) {
            if (!source.owner().isEnabled()) {
                continue; // its plugin was disabled: its art leaves the pack
            }
            try {
                SortedMap<String, byte[]> files;
                String origin;
                if (source.folder() != null) {
                    files = PackFiles.fromDirectory(source.folder());
                    origin = "plugin " + source.owner().getName() + ", folder " + source.folder();
                    if (!Files.isDirectory(source.folder())) {
                        problems.add(source.id() + ": the folder " + source.folder() + " " + source.owner().getName()
                                + " registered does not exist.");
                    }
                } else {
                    files = onlyPackFiles(PackFiles.fromJar(jarOf(source.owner()), source.jarRoot()));
                    origin = "plugin " + source.owner().getName() + ", jar" + (source.jarRoot().isEmpty() ? ""
                            : " " + source.jarRoot());
                }
                out.add(new PackAssembler.Source(source.id(), origin, files));
            } catch (IOException | RuntimeException | URISyntaxException ex) {
                problems.add(source.id() + " (" + source.owner().getName() + ") could not be read: " + ex.getMessage()
                        + "; left out.");
            }
        }
        Path dropIns = dropInFolder();
        try {
            ensureDropInFolder(dropIns);
        } catch (IOException ex) {
            problems.add("could not create " + dropIns + ": " + ex.getMessage());
        }
        if (Files.isDirectory(dropIns)) {
            List<Path> entries;
            try (Stream<Path> list = Files.list(dropIns)) {
                entries = list.sorted().toList();
            } catch (IOException ex) {
                problems.add("could not list " + dropIns + ": " + ex.getMessage());
                entries = List.of();
            }
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean zip = Files.isRegularFile(entry) && name.toLowerCase(Locale.ROOT).endsWith(".zip");
                if (!Files.isDirectory(entry) && !zip) {
                    continue; // README.txt and the like
                }
                String id = zip ? name.substring(0, name.length() - 4) : name;
                if (!ID.matcher(id).matches() || id.equals(OWN)) {
                    problems.add("pack/" + FOLDER + "/" + name + ": '" + id + "' is not a usable source id (lower-case"
                            + " letters, digits, _ . - and not 'craftbridge'); left out. Rename it.");
                    continue;
                }
                if (out.stream().anyMatch(s -> s.id().equals(id))) {
                    problems.add("pack/" + FOLDER + "/" + name + ": a plugin already registered the source '" + id
                            + "'; the folder is left out. Rename it.");
                    continue;
                }
                try {
                    SortedMap<String, byte[]> files = zip ? PackFiles.fromJar(entry, "") : PackFiles.fromDirectory(entry);
                    out.add(new PackAssembler.Source(id, "pack/" + FOLDER + "/" + name, files));
                } catch (IOException | RuntimeException ex) {
                    problems.add("pack/" + FOLDER + "/" + name + " could not be read: " + ex.getMessage() + "; left out.");
                }
            }
        }
        return out;
    }

    /** From a whole jar, only what belongs in a pack (not its classes and plugin.yml). */
    private static SortedMap<String, byte[]> onlyPackFiles(SortedMap<String, byte[]> files) {
        SortedMap<String, byte[]> out = new TreeMap<>();
        files.forEach((path, bytes) -> {
            if (path.startsWith("assets/") || path.equals(Glyphs.FILE) || path.equals("pack.png")) {
                out.put(path, bytes);
            }
        });
        return out;
    }

    private static Path jarOf(Plugin owner) throws URISyntaxException {
        return Path.of(owner.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static void ensureDropInFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("README.txt"), README, StandardCharsets.UTF_8);
        }
    }

    private static final String README = """
            Drop-in sources for CraftBridge's resource pack
            ===============================================

            Every folder here (or .zip file) is added to the one pack CraftBridge builds and sends.
            Its name is the source's id: lower case, digits, _ . and - (e.g. lilahcraft-clocks).

              lilahcraft-clocks/
                assets/<namespace>/...   laid out like any resource pack
                glyphs.json              optional: font glyphs (see CraftBridge's README)

            A downloaded pack can go in as it is: its pack.mcmeta is ignored (CraftBridge writes
            its own) and a pack zipped inside one extra folder is read from that folder.

            Then run /craftbridge pack rebuild (or restart). The console lists every source,
            every file that changes Minecraft's own assets, and any conflict between sources;
            resource-pack.priority in config.yml decides who wins a conflict.
            """;
}
