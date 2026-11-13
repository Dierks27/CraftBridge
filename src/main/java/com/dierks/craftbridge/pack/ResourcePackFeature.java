package com.dierks.craftbridge.pack;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.config.CraftBridgeConfig;
import com.dierks.craftbridge.items.CustomItemDef;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.util.Text;
import com.dierks.craftbridge.workbench.BlockKind;
import com.dierks.craftbridge.workbench.DisplayManager;
import com.dierks.craftbridge.workbench.WorkbenchFeature;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The resource pack behind {@code display.mode: model} and custom item art: builds it from the
 * jar and the art folder, writes it to {@code plugins/CraftBridge/pack/craftbridge-java.zip},
 * offers it to Java players and decides who sees the model displays.
 *
 * <p>Custom item art ({@link ItemArt}) comes from {@code plugins/CraftBridge/pack/items/}: a
 * custom item with {@code <id>.png} there is drawn with it by every client that loaded the
 * pack, and as its plain base item by everyone else. The pack is sent when a block uses
 * {@code display.mode: model} or any custom item has art.
 *
 * <p>Since 0.19.0 this is the server's only pack: {@link PackSources} adds every other plugin's
 * art (through the {@link com.dierks.craftbridge.api.CraftBridgePack} API) and the drop-in
 * folders in {@code pack/sources/}, and {@link PackAssembler} merges them with CraftBridge's own
 * files. It is built once every plugin has enabled ({@link ServerLoadEvent}), and again on
 * {@code /craftbridge pack rebuild} or a plugin's {@code requestRebuild()}, after which everyone
 * online is sent the new pack.
 *
 * <p>Delivery is {@code resource-pack.url} (a copy the admin uploaded) or, while that is blank,
 * the built-in web server ({@code resource-pack.host}). Either way the client is sent the SHA-1
 * of the pack this jar built, so an out-of-date upload is refused by the client rather than
 * shown half-right. With neither, nothing is sent and every model display stays hidden.
 *
 * <p>The pack is offered while the player is still configuring (before they enter the world),
 * so the model is there on the first frame; a player whose answer comes later, or who joined
 * before a reload, is handled in game. A model display is shown to a player once their client
 * reports the pack loaded ({@link #seesModels(Player)}), and hidden again if it is declined,
 * fails or is removed. Bedrock players never load a Java pack; they see model displays only
 * with {@code bedrock.show-displays: true}.
 */
public final class ResourcePackFeature implements CraftBridgePlugin.Feature, Listener {

    /** The pack's id when {@code resource-pack.id} is not set. */
    public static final UUID PACK_ID = PackFiles.JAVA_PACK_ID;
    public static final String ZIP_NAME = "craftbridge-java.zip";

    /** How long a joining player may take to answer the download prompt before they are let in anyway. */
    private static final long CONFIGURE_WAIT_SECONDS = 60;

    /**
     * Players whose client reported this pack loaded, with the SHA-1 it loaded. Static so it
     * survives /craftbridge reload (the clients still have the pack); cleared as players leave.
     */
    private static final Map<UUID, String> LOADED = new ConcurrentHashMap<>();

    /** How the pack reaches players: the URL and the hash the client checks. Null when it is not sent. */
    private record Delivery(URI uri, String sha1) {
    }

    private final CraftBridgePlugin plugin;
    /** Players offered the pack during configuration on this connection (the answer may arrive in game). */
    private final Set<UUID> offeredWhileConfiguring = ConcurrentHashMap.newKeySet();
    private volatile Delivery delivery;
    private volatile String sha1 = "";
    /** Custom item art in the last pack built. */
    private volatile ItemArt.Result art = ItemArt.Result.none();
    private PackHttpServer server;
    private BedrockPlayers bedrock;
    /** {@code resource-pack.id}: the same id with a new hash makes clients swap packs. */
    private UUID packId = PACK_ID;
    /** The last build's sources, glyphs and problems; null before the first build. */
    private volatile PackAssembler.Result assembled;
    /** True once the pack was built and delivery chosen (at ServerLoadEvent, or at once on a reload). */
    private boolean active;

    public ResourcePackFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "resource-pack";
    }

    @Override
    public void enable() {
        bedrock = BedrockPlayers.detect(plugin.getClass().getClassLoader(), plugin.getLogger());
        packId = plugin.config().resourcePackId();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        DisplayManager displays = displays();
        if (displays != null) {
            displays.viewers(this::seesModels);
        }
        if (plugin.serverLoaded()) {
            activate(); // a /craftbridge reload: every plugin has registered its art already
        }
        // Otherwise onServerLoad builds it, once every plugin has enabled and registered its art.
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        plugin.markServerLoaded();
        if (!active) {
            activate();
        }
    }

    private void activate() {
        active = true;
        buildAndDeliver(false);
        plugin.packSources().rebuildWith(() -> rebuild(null));
    }

    /**
     * {@code /craftbridge pack rebuild} and the API's requestRebuild(): read every source again,
     * build, and send the new pack to everyone online. When the build fails the pack already
     * sent stays.
     *
     * @param reportTo told the outcome, or null
     */
    public void rebuild(CommandSender reportTo) {
        if (!active) {
            if (reportTo != null) {
                reportTo.sendMessage(Text.msg("<yellow>The pack is built once the server has finished starting."));
            }
            return;
        }
        boolean built = buildAndDeliver(true);
        if (reportTo == null) {
            return;
        }
        PackAssembler.Result result = assembled;
        if (!built || result == null) {
            reportTo.sendMessage(Text.msg("<red>The pack could not be built; the one already sent stays. See the console."));
            return;
        }
        Delivery to = delivery;
        reportTo.sendMessage(Text.msg("<green>Pack rebuilt: <white>" + result.sources().size() + "<green> source(s), <white>"
                + result.files().size() + "<green> files, SHA-1 <white>" + sha1 + "<green>."
                + (to == null ? " <yellow>Not sent to players (see /craftbridge pack)."
                : " Everyone online who does not have it yet is sent it now.")));
        if (!result.errors().isEmpty() || !result.warnings().isEmpty()) {
            reportTo.sendMessage(Text.msg("<yellow>" + result.errors().size() + " conflict(s), " + result.warnings().size()
                    + " warning(s); the console lists them."));
        }
    }

    /**
     * Build the pack and decide how it reaches players, then bring everyone online up to date.
     *
     * @param again a rebuild while running: a failed build keeps the pack already sent
     * @return false when the pack could not be built
     */
    private boolean buildAndDeliver(boolean again) {
        CraftBridgeConfig config = plugin.config();
        byte[] zip = buildJavaPack();
        if (zip == null && again) {
            return false;
        }
        boolean blocksNeedIt = anyModelDisplays(config);
        boolean itemsNeedIt = !art.textured().isEmpty();
        PackAssembler.Result result = assembled;
        boolean othersNeedIt = result != null && result.sources().stream()
                .anyMatch(s -> !s.id().equals(PackSources.OWN) && (s.files() > 0 || s.glyphs() > 0));
        Delivery previous = delivery;
        delivery = null;
        if (!blocksNeedIt && !itemsNeedIt && !othersNeedIt) {
            plugin.getLogger().info("Resource pack: not sent, " + (workbenchOn()
                    ? "both blocks use display.mode: head" : "the Linked Workbench is off")
                    + ", no custom item has art and no other source adds anything.");
        } else if (zip != null) {
            delivery = chooseDelivery(config, zip);
            if (delivery == null) {
                String why = !config.resourcePackEnabled() ? "resource-pack.enabled is false"
                        : config.resourcePackUrl().isEmpty() && !config.resourcePackHostEnabled()
                        ? "no resource-pack.url and resource-pack.host is off" : "see the warning above";
                List<String> so = new ArrayList<>();
                if (blocksNeedIt) {
                    so.add("the Linked Workbench and Combo Chest " + (config.resourcePackDeliverable()
                            ? "models stay hidden and players see the plain blocks"
                            : "show their heads until resource-pack.url is set and the zip uploaded"));
                }
                if (itemsNeedIt) {
                    so.add("custom items keep the look of their base item");
                }
                if (othersNeedIt) {
                    so.add("the other sources' art is not shown");
                }
                plugin.getLogger().info("Resource pack: not sent to players (" + why + "), so "
                        + String.join(", and ", so) + ".");
            }
        }
        if (delivery == null && server != null) {
            server.stop();
            server = null;
        }
        if (delivery == null && (previous != null || !again)) {
            // A client keeps a server pack until it is told to drop it or leaves. Nothing is sent
            // any more, so take back the pack sent before this reload: otherwise a texture whose
            // PNG was just removed stays on screen until the player rejoins. Everyone, not only
            // those known to have loaded it: dropping a pack a client lacks does nothing, and it
            // also cancels one still waiting on the prompt or downloading.
            for (Player player : Bukkit.getOnlinePlayers()) {
                takeBack(player);
            }
        }
        // Players online through a reload or a rebuild (or a late enable) are brought up to date
        // in game: the same pack id with the new hash replaces the one their client has.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (seesModels(player)) {
                showTo(player);
            } else {
                offerInGame(player);
            }
        }
        return zip != null;
    }

    @Override
    public void disable() {
        plugin.packSources().rebuildWith(null);
        active = false;
        HandlerList.unregisterAll(this);
        DisplayManager displays = displays();
        if (displays != null) {
            displays.viewers(null);
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        delivery = null;
        offeredWhileConfiguring.clear();
    }

    // ---- building and serving ------------------------------------------------------------

    /**
     * Build the pack: CraftBridge's own source (the jar, the custom item art and the overrides,
     * which win over both) merged with every other source ({@link PackSources}); write it to the
     * data folder and log its sources, hash and problems.
     */
    private byte[] buildJavaPack() {
        try {
            SortedMap<String, byte[]> own = PackFiles.fromJar(plugin.jarFile().toPath(), PackFiles.JAR_ROOT + "java/");
            SortedMap<String, byte[]> overrides = PackFiles.fromDirectory(packFolder().resolve("overrides").resolve("java"));
            // A block's _active and _lid models (in the jar or the overrides) get their cases.
            java.util.Set<String> present = new java.util.HashSet<>(own.keySet());
            present.addAll(overrides.keySet());
            own = BlockVariants.withVariantCases(own, present,
                    java.util.Map.of("linked_workbench", "crafting_table", "combo_chest", "barrel"));
            try {
                art = buildArt(own, overrides.keySet());
            } catch (IOException | RuntimeException ex) {
                // The block models do not depend on the art: build the pack without it.
                art = ItemArt.Result.none();
                plugin.getLogger().log(Level.WARNING, "Resource pack: custom item art left out, it could not be read", ex);
            }
            SortedMap<String, byte[]> ownFiles = PackFiles.overlay(PackFiles.overlay(own, art.files()), overrides);
            List<String> problems = new ArrayList<>();
            List<PackAssembler.Source> sources = new ArrayList<>();
            sources.add(new PackAssembler.Source(PackSources.OWN, "CraftBridge (its jar, pack/items/, pack/overrides/java/)",
                    ownFiles));
            sources.addAll(plugin.packSources().read(problems));
            int[] format = packFormat(ownFiles.get("pack.mcmeta"));
            PackAssembler.Result result = PackAssembler.assemble(sources, new PackAssembler.Settings(
                    plugin.config().resourcePackPriority(), format[0], format[1],
                    "Server pack, built by CraftBridge " + plugin.getPluginMeta().getVersion()),
                    plugin.packSources().glyphStore()::codepoint);
            report(result, problems);
            assembled = result;
            SortedMap<String, byte[]> files = result.files();
            byte[] zip = PackFiles.zip(files);
            sha1 = PackFiles.sha1(zip);
            Path out = packFolder().resolve(ZIP_NAME);
            Files.createDirectories(out.getParent());
            Path temp = out.resolveSibling(ZIP_NAME + ".tmp");
            Files.write(temp, zip);
            Files.move(temp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String url = plugin.config().resourcePackUrl();
            plugin.getLogger().info("Resource pack: wrote plugins/" + plugin.getName() + "/pack/" + ZIP_NAME + " ("
                    + files.size() + " files, " + Math.max(1, zip.length / 1024) + " KB, pack format " + format[0] + "."
                    + format[1] + "), SHA-1 " + sha1 + ", id " + packId + "."
                    + (url.isEmpty() ? "" : " Upload this file to " + url + " whenever this SHA-1 changes (a CraftBridge"
                    + " update, or a reload that changed the custom item art)."));
            return zip;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Resource pack: could not be built; nothing is sent", ex);
            return null;
        }
    }

    /** The build's log: the sources, every file that changes vanilla, conflicts (errors), warnings and notes. */
    private void report(PackAssembler.Result result, List<String> problems) {
        java.util.logging.Logger log = plugin.getLogger();
        List<String> sources = new ArrayList<>();
        for (PackAssembler.SourceInfo s : result.sources()) {
            sources.add(s.id() + " (" + s.files() + " files" + (s.glyphs() > 0 ? ", " + s.glyphs() + " glyphs" : "") + ")");
        }
        log.info("Resource pack: " + result.sources().size() + " source(s), highest priority first: "
                + String.join(", ", sources) + ".");
        for (Map.Entry<String, List<String>> v : result.vanillaOverrides().entrySet()) {
            log.info("Resource pack: changes Minecraft's own assets (" + v.getValue().size() + ", from " + v.getKey()
                    + "): " + String.join(", ", v.getValue()));
        }
        if (!result.glyphs().isEmpty()) {
            long drawn = result.glyphs().stream().filter(PackAssembler.GlyphInfo::drawn).count();
            log.info("Resource pack: " + result.glyphs().size() + " glyph(s), " + drawn + " with a texture"
                    + " (/craftbridge pack glyphs lists them).");
        }
        for (String note : result.notes()) {
            log.info("Resource pack: " + note);
        }
        for (String problem : problems) {
            log.warning("Resource pack: " + problem);
        }
        for (String warning : result.warnings()) {
            log.warning("Resource pack: " + warning);
        }
        for (String error : result.errors()) {
            log.severe("Resource pack: " + error);
        }
    }

    /**
     * The resource pack format of the server's Minecraft version, read from the server itself;
     * when that fails (a server whose internals moved), the newest format CraftBridge's own
     * pack.mcmeta in the jar names.
     */
    private int[] packFormat(byte[] ownMcmeta) {
        try {
            return com.dierks.craftbridge.pack.nms.PaperPackFormat.current();
        } catch (Throwable ex) { // LinkageError on a server whose internals changed
            int[] fallback = {97, 1};
            try {
                com.google.gson.JsonArray max = PackAssembler.lenient(ownMcmeta).getAsJsonObject()
                        .getAsJsonObject("pack").getAsJsonArray("max_format");
                fallback = new int[]{max.get(0).getAsInt(), max.get(1).getAsInt()};
            } catch (RuntimeException ignored) {
                // keep the built-in numbers
            }
            plugin.getLogger().warning("Resource pack: could not read this server's pack format (" + ex
                    + "); using " + fallback[0] + "." + fallback[1] + ".");
            return fallback;
        }
    }

    private Delivery chooseDelivery(CraftBridgeConfig config, byte[] zip) {
        if (!config.resourcePackEnabled()) {
            return null;
        }
        String url = config.resourcePackUrl();
        if (!url.isEmpty()) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException ex) {
                uri = null;
            }
            String scheme = uri == null ? null : uri.getScheme();
            if (uri == null || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                plugin.getLogger().warning("Resource pack: resource-pack.url '" + url + "' is not an http(s) address;"
                        + " the pack is not sent.");
                return null;
            }
            if (config.resourcePackHostEnabled()) {
                plugin.getLogger().info("Resource pack: resource-pack.url is set, so the built-in web server stays off.");
            }
            checkUploadLater(url, sha1);
            return new Delivery(uri, sha1);
        }
        if (!config.resourcePackHostEnabled()) {
            return null;
        }
        String publicUrl = config.resourcePackPublicUrl();
        String address = config.resourcePackPublicAddress();
        if (address.isEmpty()) {
            address = Bukkit.getIp() == null ? "" : Bukkit.getIp().trim();
        }
        if (!publicUrl.isEmpty()) {
            URI base;
            try {
                base = URI.create(publicUrl);
            } catch (IllegalArgumentException ex) {
                base = null;
            }
            if (base == null || base.getHost() == null || !("http".equalsIgnoreCase(base.getScheme())
                    || "https".equalsIgnoreCase(base.getScheme()))) {
                plugin.getLogger().warning("Resource pack: resource-pack.host.public-url '" + publicUrl + "' is not an"
                        + " http(s) address; the pack is not sent.");
                return null;
            }
        } else if (address.isEmpty() || address.equals("0.0.0.0")) {
            plugin.getLogger().warning("Resource pack: resource-pack.host is on but has no public-url or public-address"
                    + " (and server.properties has no server-ip); set the address players reach this server's port"
                    + " on. The pack is not sent.");
            return null;
        }
        int port = config.resourcePackHostPort();
        if (server != null) {
            server.update(zip); // a rebuild: same port, new pack
        } else {
            try {
                server = PackHttpServer.start("0.0.0.0", port, zip);
            } catch (IOException | RuntimeException ex) {
                plugin.getLogger().warning("Resource pack: the built-in web server could not listen on port " + port
                        + " (" + ex.getMessage() + "); pick a free resource-pack.host.port. The pack is not sent.");
                return null;
            }
        }
        String link = publicUrl.isEmpty() ? PackHttpServer.url(address, port, sha1) : PackHttpServer.urlUnder(publicUrl, sha1);
        plugin.getLogger().info("Resource pack: serving it at " + link + " (players must be able to reach port "
                + port + ").");
        return new Delivery(URI.create(link), sha1);
    }

    /** Download the uploaded copy off the main thread and say in the console whether it is current. */
    private void checkUploadLater(String url, String expected) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PackUrlCheck.Result result = PackUrlCheck.check(url, expected, Duration.ofSeconds(20));
            if (result.matches()) {
                plugin.getLogger().info("Resource pack: the file at " + url + " is current (SHA-1 " + expected + ").");
            } else if (result.sha1() != null) {
                plugin.getLogger().warning("Resource pack: the uploaded pack is out of date: upload plugins/"
                        + plugin.getName() + "/pack/" + ZIP_NAME + " to " + url + " (it has SHA-1 " + result.sha1()
                        + ", this CraftBridge builds " + expected + "). Until then players' clients reject it"
                        + " and see the plain blocks and custom items.");
            } else {
                plugin.getLogger().warning("Resource pack: could not check " + url + " (" + result.problem()
                        + "). If players cannot download it either, they see the plain blocks and custom items.");
            }
        });
    }

    private Path packFolder() {
        return plugin.getDataFolder().toPath().resolve("pack");
    }

    /** {@code plugins/CraftBridge/pack/items/}, where custom item art is dropped. */
    public Path artFolder() {
        return packFolder().resolve(ItemArt.FOLDER);
    }

    /**
     * The custom item art for this pack: every custom item's id and base, the art folder (made on
     * first start, with a README), and Minecraft's own item definitions for this server's
     * version, which every generated item definition falls back to.
     */
    private ItemArt.Result buildArt(Map<String, byte[]> own, Set<String> overridden) throws IOException {
        Path folder = artFolder();
        if (!Files.isDirectory(folder)) {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("README.txt"), ART_README, StandardCharsets.UTF_8);
        }
        RecipeFeature recipes = plugin.feature(RecipeFeature.class);
        if (recipes == null) {
            return ItemArt.Result.none();
        }
        List<ItemArt.Item> items = new ArrayList<>();
        for (CustomItemDef def : recipes.customItems().all()) {
            items.add(new ItemArt.Item(def.id(), def.base().getKey().getKey(), def.base() == Material.PLAYER_HEAD));
        }
        SortedMap<String, byte[]> files = PackFiles.fromDirectory(folder);
        files.keySet().removeIf(name -> name.equalsIgnoreCase("README.txt"));
        ItemArt.Result result;
        if (files.isEmpty()) {
            result = ItemArt.Result.none();
        } else {
            String minecraft = Bukkit.getMinecraftVersion();
            Map<String, VanillaItems> tables = vanillaTables();
            String picked = VanillaItems.pick(minecraft, tables.keySet());
            if (picked == null) {
                plugin.getLogger().warning("Resource pack: this CraftBridge has no vanilla item table for Minecraft "
                        + minecraft + " (it has " + String.join(", ", tables.keySet()) + "), so no custom item gets"
                        + " art until CraftBridge is updated.");
            } else if (!picked.equals(minecraft)) {
                plugin.getLogger().info("Resource pack: using the Minecraft " + picked + " item table on " + minecraft + ".");
            }
            result = ItemArt.build(items, files, own, overridden, picked == null ? null : tables.get(picked),
                    VanillaItems.differing(tables.values()), minecraft);
        }
        for (String warning : result.warnings()) {
            plugin.getLogger().warning("Resource pack: " + warning);
        }
        // A skipped item is something the admin meant to have art, so it is a warning.
        plugin.getLogger().log(result.skipped().isEmpty() ? Level.INFO : Level.WARNING,
                "Resource pack: " + result.summary() + ".");
        return result;
    }

    /**
     * Every bundled vanilla item table, by Minecraft version. The pack is built with the one for
     * this server, but it loads on every client version pack.mcmeta allows, so the others say
     * which items those versions draw differently.
     */
    private Map<String, VanillaItems> vanillaTables() throws IOException {
        Map<String, VanillaItems> tables = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : PackFiles.fromJar(plugin.jarFile().toPath(), VanillaItems.JAR_FOLDER).entrySet()) {
            String version = VanillaItems.versionOf(file.getKey());
            if (version != null) {
                tables.put(version, VanillaItems.parse(file.getValue()));
            }
        }
        return tables;
    }

    private static final String ART_README = """
            Custom item textures for CraftBridge
            ====================================

            Name a PNG after a custom item's id and drop it here, then run /craftbridge reload.

              burned_zombie_flesh.png         the texture: square, 16x16 (or 32, 64, 128)
              burned_zombie_flesh.png.mcmeta  optional: animates a vertical strip of frames
              burned_zombie_flesh.json        optional: a whole model of your own (e.g. from
                                              Blockbench); textures it names as
                                              craftbridge:item/<name> come from <name>.png here

            The id is the one /recipe shows on the item's Save button ("Id: ..."), not its
            display name. The PNG is drawn by everyone who accepted the resource pack; players
            who declined it, and Bedrock players, keep seeing the plain base item. Items built on
            player heads keep their skin. /craftbridge pack lists which items have art, and which
            files were skipped and why.

            If players download the pack from resource-pack.url, upload the new
            craftbridge-java.zip after every reload that changed it.
            """;

    private boolean workbenchOn() {
        return plugin.feature(WorkbenchFeature.class) != null;
    }

    private boolean anyModelDisplays(CraftBridgeConfig config) {
        if (!workbenchOn()) {
            return false;
        }
        for (BlockKind kind : BlockKind.values()) {
            if (config.configuredDisplayMode(kind) == CraftBridgeConfig.DisplayMode.MODEL) {
                return true;
            }
        }
        return false;
    }

    // ---- offering the pack ---------------------------------------------------------------

    private ResourcePackRequest request(Delivery to, ResourcePackCallback callback) {
        String prompt = plugin.config().resourcePackPrompt();
        ResourcePackRequest.Builder builder = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(packId, to.uri(), to.sha1()))
                .required(plugin.config().resourcePackRequired())
                .replace(false)
                .prompt(prompt == null || prompt.isBlank() ? null : Text.mm(prompt));
        if (callback != null) {
            builder.callback(callback);
        }
        return builder.build();
    }

    /**
     * Offer the pack while the player is still configuring, and wait (off the main thread) for
     * the answer so they enter the world with the model already loaded.
     */
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        Delivery to = delivery;
        UUID id = event.getConnection().getProfile().getId();
        if (to == null || id == null || bedrock.isBedrock(id) || to.sha1().equals(LOADED.get(id))) {
            return;
        }
        CountDownLatch answered = new CountDownLatch(1);
        offeredWhileConfiguring.add(id);
        event.getConnection().getAudience().sendResourcePacks(request(to, (packId, status, audience) -> {
            if (status == ResourcePackStatus.SUCCESSFULLY_LOADED) {
                LOADED.put(id, to.sha1());
            } else if (!status.intermediate()) {
                LOADED.remove(id);
            }
            if (!status.intermediate()) {
                answered.countDown();
            }
        }));
        try {
            if (!answered.await(CONFIGURE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                plugin.debug("No answer to the resource pack from " + event.getConnection().getProfile().getName()
                        + " within " + CONFIGURE_WAIT_SECONDS + "s; letting them in, the answer is handled in game.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (delivery == null && LOADED.containsKey(player.getUniqueId())) {
            // Accepted during configuration from an offer made before a reload that stopped sending it.
            takeBack(player);
            return;
        }
        if (seesModels(player)) {
            showTo(player);
        } else if (!offeredWhileConfiguring.contains(player.getUniqueId())) {
            offerInGame(player);
        }
    }

    /** Tell a Java client to drop this pack (it answers DISCARDED, which lands in {@link #onPackStatus}). */
    private void takeBack(Player player) {
        LOADED.remove(player.getUniqueId());
        if (!bedrock.isBedrock(player.getUniqueId())) {
            player.removeResourcePacks(packId);
        }
    }

    /** Offer the pack to a player already in the world (joined before a reload, or not offered while configuring). */
    private void offerInGame(Player player) {
        Delivery to = delivery;
        if (to == null || bedrock.isBedrock(player.getUniqueId()) || to.sha1().equals(LOADED.get(player.getUniqueId()))) {
            return;
        }
        player.sendResourcePacks(request(to, null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!packId.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> plugin.getLogger().warning("Resource pack: "
                    + player.getName() + "'s client reported " + event.getStatus() + " for "
                    + (delivery == null ? "the pack" : delivery.uri()) + (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD
                    ? " (the client could not load it; check the console warnings from the last build)."
                    : " (can that address be reached from outside? resource-pack.host.port must be open/relayed)."));
            default -> {
            }
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                Delivery to = delivery;
                if (to != null) {
                    LOADED.put(player.getUniqueId(), to.sha1());
                } else {
                    takeBack(player); // an offer from before a reload that stopped sending the pack
                    return;
                }
            }
            case ACCEPTED, DOWNLOADED -> {
                return; // still on its way
            }
            default -> LOADED.remove(player.getUniqueId()); // declined, failed or removed
        }
        if (seesModels(player)) {
            showTo(player);
        } else {
            hideFrom(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // A server's pack only lasts for the connection: the next join offers it again.
        LOADED.remove(event.getPlayer().getUniqueId());
        offeredWhileConfiguring.remove(event.getPlayer().getUniqueId());
    }

    // ---- who sees model displays ---------------------------------------------------------

    /**
     * Whether this player sees the model displays: a Java player whose client loaded this
     * exact pack, or a Bedrock player when bedrock.show-displays is on.
     */
    public boolean seesModels(Player player) {
        UUID id = player.getUniqueId();
        if (bedrock != null && bedrock.isBedrock(id)) {
            return plugin.config().bedrockShowDisplays();
        }
        Delivery to = delivery;
        return to != null && to.sha1().equals(LOADED.get(id));
    }

    private DisplayManager displays() {
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        return workbench == null ? null : workbench.displays();
    }

    private void showTo(Player player) {
        DisplayManager displays = displays();
        if (displays != null) {
            displays.showTo(player);
        }
    }

    private void hideFrom(Player player) {
        DisplayManager displays = displays();
        if (displays != null) {
            displays.hideFrom(player);
        }
    }

    // ---- /craftbridge pack | geyser export -------------------------------------------------

    /** {@code /craftbridge pack}: how the pack is delivered and who has it. */
    public void status(CommandSender sender) {
        Delivery to = delivery;
        sender.sendMessage(Text.msg("<gray>Pack SHA-1 <white>" + (sha1.isEmpty() ? "(not built)" : sha1)
                + " <gray>| " + (to == null ? "<red>not sent to players" : "<green>sent from <white>" + to.uri())));
        int loaded = 0;
        int bedrockCount = 0;
        int online = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            online++;
            if (bedrock.isBedrock(player.getUniqueId())) {
                bedrockCount++;
            } else if (to != null && to.sha1().equals(LOADED.get(player.getUniqueId()))) {
                loaded++;
            }
        }
        sender.sendMessage(Text.msg("<gray>" + loaded + " of " + (online - bedrockCount) + " Java player(s) online loaded it; "
                + bedrockCount + " Bedrock player(s) " + (plugin.config().bedrockShowDisplays() ? "see" : "do not see")
                + " the models."));
        ItemArt.Result items = art;
        sender.sendMessage(Text.msg("<gray>Custom item art: <white>" + items.textured().size() + "<gray> item(s) from plugins/"
                + Text.escape(plugin.getName()) + "/pack/items/" + (items.skipped().isEmpty() ? ""
                : ", <yellow>" + items.skipped().size() + "<gray> skipped")));
        for (ItemArt.Textured t : items.textured()) {
            sender.sendMessage(Text.msg("<dark_gray> - <white>" + t.id() + " <gray>on " + t.base() + ", "
                    + Text.escape(t.size()) + ", " + (t.customModel() ? "custom model" : "generated model")
                    + " <dark_gray>(item definition: " + Text.escape(t.definition()) + ")"));
        }
        for (ItemArt.Skipped s : items.skipped()) {
            sender.sendMessage(Text.msg("<dark_gray> - <yellow>" + Text.escape(s.name()) + " <gray>skipped: "
                    + Text.escape(s.reason())));
        }
        for (String warning : items.warnings()) {
            sender.sendMessage(Text.msg("<dark_gray> ! <gold>" + Text.escape(warning)));
        }
        PackAssembler.Result result = assembled;
        if (result == null) {
            sender.sendMessage(Text.msg("<gray>Sources: <yellow>not built yet (the pack is built once the server has started)"));
            return;
        }
        sender.sendMessage(Text.msg("<gray>Pack id <white>" + packId + "<gray>, " + result.files().size()
                + " files from " + result.sources().size() + " source(s), highest priority first:"));
        for (PackAssembler.SourceInfo source : result.sources()) {
            sender.sendMessage(Text.msg("<dark_gray> - <white>" + Text.escape(source.id()) + " <gray>" + source.files()
                    + " files" + (source.glyphs() > 0 ? ", " + source.glyphs() + " glyphs" : "") + " <dark_gray>("
                    + Text.escape(source.origin()) + ")"));
        }
        int vanilla = result.vanillaOverrides().values().stream().mapToInt(List::size).sum();
        sender.sendMessage(Text.msg("<gray>" + vanilla + " file(s) change Minecraft's own assets; <white>"
                + result.glyphs().size() + "<gray> glyph(s); " + (result.errors().isEmpty() ? "no conflicts"
                : "<red>" + result.errors().size() + " conflict(s)<gray>") + ", " + result.warnings().size()
                + " warning(s) <dark_gray>(the console has the details; /craftbridge pack rebuild | glyphs)"));
    }

    /** {@code /craftbridge pack glyphs}: every glyph key, its character and where its texture comes from. */
    public void glyphs(CommandSender sender) {
        PackAssembler.Result result = assembled;
        Map<String, PackAssembler.GlyphInfo> inPack = new TreeMap<>();
        if (result != null) {
            for (PackAssembler.GlyphInfo g : result.glyphs()) {
                inPack.put(g.key(), g);
            }
        }
        SortedMap<String, Integer> all = new TreeMap<>(plugin.packSources().glyphStore().all());
        sender.sendMessage(Text.msg("<gray>Glyphs: <white>" + all.size() + "<gray> key(s) in plugins/"
                + Text.escape(plugin.getName()) + "/pack/glyphs.yml"));
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            PackAssembler.GlyphInfo g = inPack.get(e.getKey());
            String where = g == null ? "<yellow>no texture (no source declares it)"
                    : (g.drawn() ? "<gray>" : "<yellow>missing ") + Text.escape(g.texture()) + " <dark_gray>from "
                    + Text.escape(g.source()) + ", height " + g.height() + ", ascent " + g.ascent();
            sender.sendMessage(Text.msg("<dark_gray> - <white>" + Text.escape(e.getKey()) + " <gray>"
                    + Glyphs.hex(e.getValue()) + " <white>" + Glyphs.text(e.getValue()) + " " + where));
        }
        sender.sendMessage(Text.msg("<gray>Fixed spaces: <white>craftbridge:space/-1<gray> to <white>/-8<gray>, /-16 ... /-1024"
                + " (U+F801-U+F80F) and <white>craftbridge:space/1<gray> ... /1024 (U+F821-U+F82F)."));
    }

    /** Custom item art in the pack last built (empty when none). */
    public ItemArt.Result art() {
        return art;
    }

    /** True while the pack is offered to players at all. */
    public boolean sent() {
        return delivery != null;
    }

    /** {@code /craftbridge geyser export}. */
    public void exportGeyser(CommandSender sender) {
        try {
            SortedMap<String, byte[]> bedrockPack = PackFiles.overlay(
                    PackFiles.fromJar(plugin.jarFile().toPath(), PackFiles.JAR_ROOT + "bedrock/"),
                    PackFiles.fromDirectory(packFolder().resolve("overrides").resolve("bedrock")));
            List<GeyserExport.BedrockItem> items = new ArrayList<>();
            RecipeFeature recipes = plugin.feature(RecipeFeature.class);
            for (ItemArt.Textured t : art.textured()) {
                CustomItemDef def = recipes == null ? null : recipes.customItems().get(t.id());
                if (t.png() == null || def == null) {
                    continue; // a custom model with no texture of its own has nothing to show as an icon
                }
                items.add(new GeyserExport.BedrockItem(t.id(), t.base(),
                        PlainTextComponentSerializer.plainText().serialize(Text.item(def.name())), t.png()));
            }
            String folder = plugin.config().bedrockGeyserFolder();
            List<String> lines = GeyserExport.export(bedrockPack,
                    resource(PackFiles.JAR_ROOT + "geyser/" + GeyserExport.MAPPINGS),
                    resource(PackFiles.JAR_ROOT + "geyser/" + GeyserExport.DISPLAY_MAPPINGS),
                    items,
                    plugin.getDataFolder().toPath().resolve("geyser"),
                    plugin.getDataFolder().toPath().getParent(),
                    folder.isEmpty() ? null : Path.of(folder));
            for (String line : lines) {
                sender.sendMessage(Text.msg("<gray>" + Text.escape(line)));
                plugin.getLogger().info("[geyser export] " + line);
            }
        } catch (IOException | RuntimeException ex) {
            sender.sendMessage(Text.msg("<red>Export failed: " + Text.escape(String.valueOf(ex.getMessage()))));
            plugin.getLogger().log(Level.WARNING, "Geyser export failed", ex);
        }
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                throw new IOException("the jar has no " + path);
            }
            return in.readAllBytes();
        }
    }
}
