package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
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
        BlockKind kind = WorkbenchItems.kindOf(event.getItemInHand());
        if (kind == null) {
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
                if (WorkbenchItems.kindOf(inHand) != kind) {
                    return; // item changed hands between the event and now
                }
                inHand.setAmount(inHand.getAmount() - 1);
            }
            feature.place(block, player, kind);
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
        if (block == null || (block.getType() != Material.CRAFTING_TABLE && block.getType() != Material.BARREL)) {
            return;
        }
        WorkbenchRecord record = feature.store().at(block);
        if (record == null || record.kind().block() != block.getType()) {
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (record.kind() == BlockKind.COMBO_CHEST) {
            // The barrel under a Combo Chest is a terminal, never storage: our GUI replaces the vanilla one.
            new ComboChestMenu(plugin, feature, event.getPlayer(), record).open(event.getPlayer());
            return;
        }
        // Placing a linked table is the opt-in: every right-click (sneaking or not) opens the
        // linked crafting menu with nearby storage shown as phantom inventory slots.
        feature.sessions().open(event.getPlayer(), record);
    }

    // ---- phantom slots -------------------------------------------------------------

    /**
     * Clicks in the linked view, decided by {@link WorkbenchClicks} (which is unit tested for
     * every combination). Real slots, the crafting grid and the result are always left to
     * vanilla, so hand-placing items and crafting behave exactly as at a plain crafting
     * table. A phantom slot is special in one direction only: taking from it pulls the real
     * items out of storage, while putting something into it is an ordinary place, because
     * the slot genuinely is empty.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        PhantomManager phantoms = feature.phantoms();
        if (phantoms == null || !(event.getWhoClicked() instanceof Player player)
                || !feature.sessions().isLinkedView(player, event.getView())) {
            return;
        }
        final int slot = event.getRawSlot();
        WorkbenchClicks.Slot kind = phantoms.slotKind(player, slot);
        WorkbenchClicks.Action action = WorkbenchClicks.decide(kind,
                Items.isEmpty(event.getCursor()), event.getClick().name());
        if (action == WorkbenchClicks.Action.ALLOW) {
            phantoms.rebuildLater(player);
            return;
        }
        event.setCancelled(true);
        phantoms.markResendAll(player);
        // Pulls run right here, in this tick, so one click is one click: the items move, the
        // cursor is set and the slot's real contents go out before the cancelled click's own
        // re-sync. Deferring this to the next tick is what made the client keep drawing a
        // phantom the player had already taken, costing a second and third click.
        switch (action) {
            case PULL_ONE -> phantoms.pull(player, slot, PullPlanner.Mode.ONE);
            case PULL_HALF -> phantoms.pull(player, slot, PullPlanner.Mode.HALF);
            case PULL_ALL -> phantoms.pull(player, slot, PullPlanner.Mode.ALL);
            case PAGE -> {
                PhantomManager.Button button = phantoms.buttonAt(player, slot);
                if (button != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> phantoms.turnPage(player, button));
                }
            }
            default -> {
                phantoms.resend(player, slot);
                phantoms.rebuildLater(player, true);
            }
        }
    }

    /**
     * Drags only ever put items down, never take them, so a drag is never refused on account
     * of a phantom: the slots it writes into are genuinely empty. The snapshot is rebuilt
     * afterwards so the display catches up.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        PhantomManager phantoms = feature.phantoms();
        if (phantoms == null || !(event.getWhoClicked() instanceof Player player)
                || !feature.sessions().isLinkedView(player, event.getView())) {
            return;
        }
        phantoms.rebuildLater(player);
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        feature.sessions().end(event.getPlayer(), true);
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
