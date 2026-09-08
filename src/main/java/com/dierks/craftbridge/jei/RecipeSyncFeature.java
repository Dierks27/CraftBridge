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
 *   <li>repeat for everyone whenever CraftBridge's custom recipes change, so JEI updates
 *       live — no rejoin, no {@code /jeiproxy handshake}.</li>
 * </ol>
 * The payload must fit vanilla's 1 MiB custom-payload limit (Fabric's packet splitter is
 * Fabric-only); recipe types are dropped from the end of {@code jei.recipe-sync.types}
 * until it does.
 */
public final class RecipeSyncFeature implements CraftBridgePlugin.Feature, Listener {

    public static final String CHANNEL = "fabric:recipe_sync";
    /** Vanilla ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE (and Bukkit's Messenger limit). */
    private static final int MAX_PAYLOAD = 1048576;

    private final CraftBridgePlugin plugin;
    private RecipeSyncEncoder encoder;
    private byte[] cachedPayload;
    private int cachedCount;
    private final Set<UUID> synced = new HashSet<>();
    private boolean resyncScheduled;

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
        plugin.getLogger().info("JEI recipe sync: " + cachedCount + " recipe(s) encoded (" + cachedPayload.length
                + " bytes) on " + CHANNEL + "; JEI clients get server recipes live.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncIfListening(player);
        }
    }

    @Override
    public void disable() {
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
        // it a tick; PlayerRegisterChannelEvent covers the late case.
        Bukkit.getScheduler().runTask(plugin, () -> syncIfListening(event.getPlayer()));
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            syncIfListening(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synced.remove(event.getPlayer().getUniqueId());
    }

    /** Re-encode and re-send to everyone (debounced to one tick). */
    public void scheduleResyncAll() {
        if (!isActive() || resyncScheduled) {
            return;
        }
        resyncScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            resyncScheduled = false;
            try {
                encodeNow();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("JEI recipe sync: re-encoding failed (" + ex + "); clients keep the previous set.");
                return;
            }
            synced.clear();
            int n = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (syncIfListening(player)) {
                    n++;
                }
            }
            plugin.getLogger().info("JEI recipe sync: recipes changed, re-sent " + cachedCount + " recipe(s) to " + n + " JEI client(s).");
        });
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
        plugin.debug("JEI recipe sync: sent " + cachedCount + " recipe(s) (" + cachedPayload.length + " bytes) to " + player.getName());
        return true;
    }

    /** Encode with the configured type list, dropping types from the end until the payload fits. */
    private void encodeNow() {
        List<String> types = new ArrayList<>(new LinkedHashSet<>(plugin.config().jeiRecipeSyncTypes()));
        while (true) {
            Set<String> allowed = types.isEmpty() ? null : new LinkedHashSet<>(types);
            RecipeSyncEncoder.Encoded encoded = encoder.encode(allowed);
            if (encoded.bytes().length <= MAX_PAYLOAD) {
                cachedPayload = encoded.bytes();
                cachedCount = encoded.recipeCount();
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
