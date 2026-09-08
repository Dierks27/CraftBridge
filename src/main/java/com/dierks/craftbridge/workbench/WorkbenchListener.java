package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.integration.ContainerAccess;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/** All world/player events for the Linked Workbench. */
public final class WorkbenchListener implements Listener {

    private final CraftBridgePlugin plugin;
    private final WorkbenchFeature feature;

    public WorkbenchListener(CraftBridgePlugin plugin, WorkbenchFeature feature) {
        this.plugin = plugin;
        this.feature = feature;
    }

    // ---- placing ----------------------------------------------------------------

    /**
     * Our head item is placed as a crafting table. The event is cancelled (so the head
     * block and the item are reverted by vanilla) and the table is placed next tick —
     * modifying the block during the event would be undone by the cancel.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!WorkbenchItems.isPlaceItem(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        EquipmentSlot hand = event.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!block.getType().isAir() && !block.isReplaceable()) {
                return;
            }
            if (player.getGameMode() != GameMode.CREATIVE) {
                ItemStack inHand = player.getInventory().getItem(hand);
                if (!WorkbenchItems.isPlaceItem(inHand)) {
                    return; // item changed hands between the event and now
                }
                inHand.setAmount(inHand.getAmount() - 1);
            }
            feature.place(block, player);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
        });
    }

    // ---- breaking / destruction ------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!feature.store().isLinked(block)) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        feature.unplace(block, event.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (feature.store().isLinked(event.getBlock())) {
            feature.unplace(event.getBlock(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (feature.store().isLinked(block)) {
                it.remove(); // vanilla must not drop a plain crafting table
                Bukkit.getScheduler().runTask(plugin, () -> {
                    feature.unplace(block, true);
                    block.setType(Material.AIR);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (feature.store().isLinked(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (feature.store().isLinked(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- using ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (ContainerAccess.isSynthetic() || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CRAFTING_TABLE) {
            return;
        }
        WorkbenchRecord record = feature.store().at(block);
        if (record == null) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            new StorageMenu(plugin, feature.scanner(), player, record).open(player);
        } else {
            feature.sessions().open(player, record);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && feature.sessions().isLinkedView(player, event.getView())) {
            feature.sessions().end(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        feature.sessions().end(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        endLater(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        endLater(event.getPlayer());
    }

    private void endLater(Player player) {
        if (feature.sessions().of(player) == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (feature.sessions().of(player) != null) {
                player.closeInventory(); // fires InventoryCloseEvent -> session ends and items return
                feature.sessions().end(player, true);
            }
        });
    }

    // ---- world loading ------------------------------------------------------------

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        feature.displays().sweepChunk(event.getChunk());
    }

}
