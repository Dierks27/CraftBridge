package com.dierks.craftbridge.jei;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.recipes.RecipeFeature;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Make JEI on Fabric clients show the <em>server's</em> recipes.
 *
 * <p>JEI 26.2 gets its recipe list from Fabric API's recipe synchronisation
 * ({@code fabric-recipe-api-v1}): the server sends a {@code fabric:recipe_sync} payload —
 * a list of (serializer id, recipes encoded with that serializer's stream codec) — and
 * the Fabric client hands it to JEI through {@code ClientRecipeSynchronizedEvent}. On a
 * server that never sends it, JEI falls back to the recipe JSONs bundled with the
 * <em>client</em>, so custom recipes never appear. This feature speaks that protocol:
 * <ol>
 *   <li>register {@code fabric:recipe_sync} as an outgoing channel (Paper only delivers a
 *       plugin message to a client that announced the channel — Fabric clients with the
 *       recipe API do);</li>
 *   <li>on join (once the client's channel list is in) send the encoded recipe set, then
 *       re-send vanilla's {@code ClientboundUpdateRecipesPacket} to that player, because
 *       JEI (re)starts on that packet and by the time a Paper plugin can act the original
 *       one has already gone out;</li>
 *   <li>repeat for everyone whenever the server's recipe set changes, so JEI updates live —
 *       no rejoin, no {@code /jeiproxy handshake}. "Changes" is not only CraftBridge's own
 *       recipes: other plugins register theirs in their own {@code onEnable}, which can be
 *       after ours, so the snapshot is also rebuilt when the server finishes loading, and
 *       whenever the live recipe count stops matching the snapshot's.</li>
 * </ol>
 * The payload must fit vanilla's 1 MiB custom-payload limit (Fabric's packet splitter is
 * Fabric-only); recipe types are dropped from the end of {@code jei.recipe-sync.types}
 * until it does.
 */
public final class RecipeSyncFeature implements CraftBridgePlugin.Feature, Listener {

    public static final String CHANNEL = "fabric:recipe_sync";
    /** Vanilla ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE (and Bukkit's Messenger limit). */
    private static final int MAX_PAYLOAD = 1048576;

    /** At most one re-encode + push per second, however many recipes change at once. */
    private static final long RESYNC_DEBOUNCE_TICKS = 20L;
    /** How often to notice that some other plugin changed the recipe set behind our back. */
    private static final long POLL_TICKS = 600L;

    private final CraftBridgePlugin plugin;
    private RecipeSyncEncoder encoder;
    private byte[] cachedPayload;
    private int cachedCount;
    private Map<String, Integer> cachedNamespaces = Map.of();
    private int snapshotRecipeCount = -1;
    private final Set<UUID> synced = new HashSet<>();
    private boolean resyncScheduled;
    private int pollTask = -1;

    public RecipeSyncFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "jei-recipe-sync";
    }

    @Override
    public void enable() {
        try {
            encoder = (RecipeSyncEncoder) Class.forName("com.dierks.craftbridge.jei.nms.PaperRecipeSyncEncoder")
                    .getDeclaredConstructor().newInstance();
            encodeNow();
        } catch (Throwable t) {
            plugin.getLogger().warning("JEI recipe sync disabled: the server-internals encoder failed to load on this Paper build ("
                    + t + "). JEI clients will only see the recipes bundled with their client.");
            encoder = null;
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        RecipeFeature recipes = plugin.feature(RecipeFeature.class);
        if (recipes != null) {
            recipes.registry().onChange(this::scheduleResyncAll);
        }
        plugin.getLogger().info("JEI recipe sync: " + describe() + " on " + CHANNEL
                + "; JEI clients get server recipes live.");
        warnAboutMissingPluginRecipes();
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollForChanges, POLL_TICKS, POLL_TICKS)
                .getTaskId();
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncIfListening(player);
        }
    }

    @Override
    public void disable() {
        if (pollTask != -1) {
            Bukkit.getScheduler().cancelTask(pollTask);
            pollTask = -1;
        }
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        synced.clear();
        cachedPayload = null;
    }

    public boolean isActive() {
        return encoder != null && cachedPayload != null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // The client's minecraft:register usually arrives before the join event, but give
        // it a tick; PlayerRegisterChannelEvent covers the late case. Always send the
        // current recipe set, never whatever was encoded when the plugin started.
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshIfStale();
            syncIfListening(event.getPlayer());
        });
        // The client's minecraft:register can lag the join by a few ticks, more through a
        // proxy, so only complain once it has clearly not arrived.
        Bukkit.getScheduler().runTaskLater(plugin, () -> reportIfNotSynced(event.getPlayer()), 100L);
    }

    /**
     * Every plugin has enabled by the time this fires, so any recipe another plugin
     * registered in its own {@code onEnable} is in the set now (this is also what a
     * {@code /reload} fires).
     */
    @EventHandler
    public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
        if (!isActive()) {
            return;
        }
        refreshIfStale();
        plugin.getLogger().info("JEI recipe sync: server finished loading, snapshot is " + describe() + ".");
        warnAboutMissingPluginRecipes();
        pushToEveryone();
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            refreshIfStale();
            syncIfListening(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synced.remove(event.getPlayer().getUniqueId());
    }

    /** Re-encode and re-send to everyone, at most once a second however many edits land. */
    public void scheduleResyncAll() {
        if (!isActive() || resyncScheduled) {
            return;
        }
        resyncScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            resyncScheduled = false;
            if (!encode("recipes changed")) {
                return;
            }
            int n = pushToEveryone();
            plugin.getLogger().info("JEI recipe sync: recipes changed, re-sent " + describe()
                    + " to " + n + " JEI client(s).");
        }, RESYNC_DEBOUNCE_TICKS);
    }

    /** Force a resend to everyone with the snapshot as it stands. Returns how many got it. */
    public int pushToEveryone() {
        synced.clear();
        int n = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (syncIfListening(player)) {
                n++;
            }
        }
        return n;
    }

    /** {@code /craftbridge jei resync}: rebuild the snapshot from scratch and push it. */
    public int resyncNow() {
        if (!isActive()) {
            return 0;
        }
        encode("manual resync");
        return pushToEveryone();
    }

    /**
     * Re-encode when the server's recipe set no longer matches the snapshot — the cheap way
     * to notice that another plugin (or a datapack reload) added or removed recipes.
     */
    private boolean refreshIfStale() {
        if (encoder == null) {
            return false;
        }
        int live;
        try {
            live = encoder.liveRecipeCount();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        if (cachedPayload != null && live == snapshotRecipeCount) {
            return false;
        }
        return encode("recipe set changed (" + snapshotRecipeCount + " -> " + live + " on the server)");
    }

    private void pollForChanges() {
        if (!isActive() || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        if (refreshIfStale()) {
            int n = pushToEveryone();
            plugin.getLogger().info("JEI recipe sync: recipe set changed elsewhere, re-sent "
                    + describe() + " to " + n + " JEI client(s).");
        }
    }

    /** Re-encode, keeping the previous payload if encoding fails. */
    private boolean encode(String why) {
        try {
            encodeNow();
            plugin.debug("JEI recipe sync: re-encoded (" + why + "): " + describe());
            return true;
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("JEI recipe sync: re-encoding failed (" + ex
                    + "); clients keep the previous set.");
            return false;
        }
    }

    /** One line for the log and for {@code /craftbridge jei}. */
    public String describe() {
        if (cachedPayload == null) {
            return "no snapshot";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cachedCount).append(" recipe(s), ").append(cachedPayload.length / 1024).append(" KiB");
        if (!cachedNamespaces.isEmpty()) {
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<String, Integer> e : cachedNamespaces.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * {@code /craftbridge jei dump <key>}: the live recipe next to the same recipe after an
     * encode/decode round trip, so what the wire does to an ingredient is a fact rather than
     * a theory.
     */
    public List<String> dump(String recipeKey) {
        List<String> out = new ArrayList<>();
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(recipeKey);
        org.bukkit.inventory.Recipe live = key == null ? null : Bukkit.getRecipe(key);
        if (live == null) {
            out.add("No recipe '" + recipeKey + "' on this server.");
            return out;
        }
        out.addAll(RecipeDump.describe("on the server", live));
        if (!isActive()) {
            out.add("Recipe sync is not active, so there is nothing to round-trip.");
            return out;
        }
        RecipeSyncEncoder.RoundTrip trip = encoder.roundTrip(recipeKey);
        if (trip.error() != null) {
            out.add("round trip FAILED: " + trip.error());
            out.add("A recipe that cannot be encoded is left out of the payload entirely.");
            return out;
        }
        out.add("wire size: " + trip.bytes() + " bytes");
        out.addAll(RecipeDump.describe("as the client receives it", trip.decoded()));
        return out;
    }

    public int syncedPlayerCount() {
        return synced.size();
    }

    /**
     * Cross-check the payload against what the Bukkit API says the server has: every
     * crafting recipe a plugin registered should be in there. If one is not, say so loudly
     * with the namespace, because that is exactly the "my recipe never shows in JEI" bug.
     */
    private void warnAboutMissingPluginRecipes() {
        if (!isActive()) {
            return;
        }
        Map<String, Integer> live = new java.util.LinkedHashMap<>();
        try {
            java.util.Iterator<org.bukkit.inventory.Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                org.bukkit.inventory.Recipe recipe;
                try {
                    recipe = it.next();
                } catch (RuntimeException ex) {
                    continue;
                }
                if (!(recipe instanceof org.bukkit.inventory.ShapedRecipe)
                        && !(recipe instanceof org.bukkit.inventory.ShapelessRecipe)) {
                    continue; // only the crafting types the payload carries
                }
                if (recipe instanceof org.bukkit.Keyed keyed
                        && !"minecraft".equals(keyed.getKey().getNamespace())) {
                    live.merge(keyed.getKey().getNamespace(), 1, Integer::sum);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return;
        }
        for (Map.Entry<String, Integer> e : live.entrySet()) {
            int encoded = cachedNamespaces.getOrDefault(e.getKey(), 0);
            if (encoded < e.getValue()) {
                plugin.getLogger().warning("JEI recipe sync: " + e.getKey() + " has " + e.getValue()
                        + " crafting recipe(s) on the server but only " + encoded
                        + " made it into the payload; those will not show in JEI."
                        + " Check jei.recipe-sync.types covers their recipe type.");
            }
        }
        if (!live.isEmpty()) {
            plugin.debug("JEI recipe sync: plugin crafting recipes on the server: " + live);
        }
    }

    private boolean syncIfListening(Player player) {
        if (!isActive() || !player.isOnline() || synced.contains(player.getUniqueId())
                || !player.getListeningPluginChannels().contains(CHANNEL)) {
            return false;
        }
        player.sendPluginMessage(plugin, CHANNEL, cachedPayload);
        try {
            encoder.resendRecipes(player);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("JEI recipe sync: could not re-send vanilla recipes to " + player.getName() + " (" + ex + ")");
        }
        synced.add(player.getUniqueId());
        // At INFO on purpose: this one line is the difference between "the payload went out"
        // and "the client never announced the channel", which is otherwise invisible.
        plugin.getLogger().info("JEI recipe sync: sent to " + player.getName() + " on " + CHANNEL
                + " (" + describe() + ")");
        return true;
    }

    /**
     * A few seconds after joining, say something if this player still has not been sent the
     * payload. A client that speaks JEI's own channels but not ours is the interesting case —
     * that is a JEI on a loader whose recipe sync is not {@code fabric:recipe_sync} (a
     * NeoForge client, say), and one line naming the channels it did register turns the next
     * mismatch into a diagnosis instead of a testing session.
     */
    private void reportIfNotSynced(Player player) {
        if (!isActive() || !player.isOnline() || synced.contains(player.getUniqueId())) {
            return;
        }
        Set<String> channels = new java.util.TreeSet<>(player.getListeningPluginChannels());
        boolean looksLikeJei = channels.stream().anyMatch(c -> c.startsWith("jei:"));
        String message = "JEI recipe sync: nothing sent to " + player.getName() + " — the client did not"
                + " register " + CHANNEL + ". Channels it did register: " + channels;
        if (looksLikeJei) {
            plugin.getLogger().warning(message + " (it speaks JEI's own channels, so this is a JEI"
                    + " client whose recipe sync uses a different channel — recipe sync needs a Fabric client.)");
        } else {
            plugin.debug(message);
        }
    }

    /** Encode with the configured type list, dropping types from the end until the payload fits. */
    private void encodeNow() {
        List<String> types = new ArrayList<>(new LinkedHashSet<>(plugin.config().jeiRecipeSyncTypes()));
        while (true) {
            Set<String> allowed = types.isEmpty() ? null : new LinkedHashSet<>(types);
            RecipeSyncEncoder.Encoded encoded = encoder.encode(allowed);
            for (String problem : encoded.problems()) {
                plugin.getLogger().warning("JEI recipe sync: left out " + problem
                        + " — it does not survive the wire, and one bad recipe makes the client"
                        + " discard the whole payload.");
            }
            if (encoded.bytes().length <= MAX_PAYLOAD) {
                cachedPayload = encoded.bytes();
                cachedCount = encoded.recipeCount();
                cachedNamespaces = encoded.byNamespace() == null ? Map.of() : encoded.byNamespace();
                try {
                    snapshotRecipeCount = encoder.liveRecipeCount();
                } catch (RuntimeException | LinkageError ex) {
                    snapshotRecipeCount = -1;
                }
                return;
            }
            if (types.isEmpty()) {
                throw new IllegalStateException("even the full type list is above 1 MiB and there is nothing left to drop");
            }
            String dropped = types.removeLast();
            plugin.getLogger().warning("JEI recipe sync: payload is " + encoded.bytes().length
                    + " bytes, above the 1 MiB custom-payload limit; dropping recipe type " + dropped + ".");
            if (types.isEmpty()) {
                throw new IllegalStateException("payload does not fit even after dropping every recipe type");
            }
        }
    }
}
