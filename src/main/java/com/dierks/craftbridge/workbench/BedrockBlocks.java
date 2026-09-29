package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.pack.BedrockPlayers;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;

/**
 * What Bedrock players see where a Linked Workbench or Combo Chest stands on its invisible
 * barrier. Geyser does not draw item displays, so without help they would see nothing there. Each
 * Bedrock player is sent the vanilla block (a crafting table, a barrel) at that spot instead,
 * for their client only: the server still has the barrier, so clicks and breaks work exactly as
 * for everyone else.
 *
 * <p>Such a block only lasts until the server sends that spot again, so it is sent whenever the
 * chunk is sent to the player, and again a tick after they click the block (the server answers a
 * click it handled itself by sending the real block back). With {@code bedrock.show-displays} on
 * (GeyserDisplayEntity draws the display) nothing is sent.
 */
public final class BedrockBlocks implements Listener {

    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private final BedrockPlayers bedrock;

    public BedrockBlocks(CraftBridgePlugin plugin, WorkbenchStore store, BedrockPlayers bedrock) {
        this.plugin = plugin;
        this.store = store;
        this.bedrock = bedrock;
    }

    public boolean isBedrock(Player player) {
        return bedrock.isBedrock(player.getUniqueId());
    }

    /** Whether this player is shown stand-in blocks at all. */
    private boolean standsIn(Player player) {
        return !plugin.config().bedrockShowDisplays() && isBedrock(player);
    }

    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        if (!standsIn(player)) {
            return;
        }
        Chunk chunk = event.getChunk();
        for (WorkbenchRecord record : new ArrayList<>(store.all())) {
            if (record.world().equals(chunk.getWorld().getName())
                    && (record.x() >> 4) == chunk.getX() && (record.z() >> 4) == chunk.getZ()) {
                send(player, record);
            }
        }
    }

    /** Send the stand-in for one block to every Bedrock player in its world (after a place or a conversion). */
    public void show(WorkbenchRecord record) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getName().equals(record.world()) && standsIn(player)) {
                send(player, record);
            }
        }
    }

    /** Every stand-in in every loaded chunk, to every Bedrock player (after a reload). */
    public void showAll() {
        for (WorkbenchRecord record : new ArrayList<>(store.all())) {
            if (record.chunkLoaded()) {
                show(record);
            }
        }
    }

    /** Send the stand-in again next tick, after the server has answered the player's click. */
    public void resendLater(Player player, WorkbenchRecord record) {
        if (standsIn(player) && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && store.byKey(record.key()) != null) {
                    send(player, record);
                }
            });
        }
    }

    private static void send(Player player, WorkbenchRecord record) {
        Block block = record.block();
        if (block != null && block.getType() == Material.BARRIER) {
            player.sendBlockChange(block.getLocation(), record.kind().block().createBlockData());
        }
    }
}
