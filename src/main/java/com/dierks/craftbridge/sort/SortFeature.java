package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Feature 3: chest sorting with per-player triggers. */
public final class SortFeature implements CraftBridgePlugin.Feature {

    private final CraftBridgePlugin plugin;
    private final Set<UUID> debugging = new HashSet<>();
    private SortService service;
    private SortSettingsStore store;
    private SortListener listener;

    public SortFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "sorting";
    }

    @Override
    public void enable() {
        service = new SortService(plugin, plugin.config().sortCategories());
        store = new SortSettingsStore(plugin, new PlayerSortSettings(
                plugin.config().sortDefaultTrigger(),
                plugin.config().sortPlayerInventoryAllowed() && plugin.config().sortPlayerInventoryDefault(),
                plugin.config().sortFeedbackDefault()));
        listener = new SortListener(plugin, this, debugging);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        PluginCommand cmd = plugin.getCommand("sort");
        if (cmd != null) {
            SortCommand executor = new SortCommand(this, debugging);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    @Override
    public void disable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        PluginCommand cmd = plugin.getCommand("sort");
        if (cmd != null) {
            cmd.setExecutor((sender, c, l, a) -> {
                sender.sendMessage(Text.msg("<red>Sorting is disabled in config.yml."));
                return true;
            });
        }
    }

    public SortService service() {
        return service;
    }

    public boolean playerInventoryAllowed() {
        return plugin.config().sortPlayerInventoryAllowed();
    }

    public PlayerSortSettings settings(Player player) {
        PlayerSortSettings s = store.get(player.getUniqueId());
        if (!playerInventoryAllowed() && s.sortPlayerInventory()) {
            s = s.withSortPlayerInventory(false);
        }
        return s;
    }

    public void update(Player player, UnaryOperator<PlayerSortSettings> change) {
        store.put(player.getUniqueId(), change.apply(settings(player)));
    }

    /**
     * Sort whatever the player has open: a supported container (plus their own
     * inventory if they opted in), or just their inventory when nothing else is open.
     */
    public void sortOpenInventory(Player player, InventoryView view) {
        if (!player.hasPermission("craftbridge.sort")) {
            player.sendMessage(Text.msg("<red>You may not sort containers."));
            return;
        }
        Inventory top = view.getTopInventory();
        if (top.getHolder(false) instanceof Menu) {
            return;
        }
        PlayerSortSettings settings = settings(player);
        if (service.isSortableContainer(top)) {
            org.bukkit.block.Block block = blockOf(top);
            if (block != null && plugin.containerAccess().isLocked(block)) {
                player.sendMessage(Text.msg("<red>That container is locked."));
                return;
            }
            boolean changed = service.sortContainer(top);
            if (settings.sortPlayerInventory()) {
                changed |= service.sortPlayerInventory(player.getInventory());
            }
            finish(player, settings, changed, top.getLocation());
            return;
        }
        if (view.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            if (!playerInventoryAllowed()) {
                player.sendMessage(Text.msg("<gray>Inventory sorting is turned off on this server."));
                return;
            }
            finish(player, settings, service.sortPlayerInventory(player.getInventory()), null);
            return;
        }
        player.sendMessage(Text.msg("<gray>Open a chest, barrel or shulker box first."));
    }

    /** Sort a container the player targeted in the world (sneak + punch). */
    public void sortNow(Player player, Inventory inventory, Location feedbackAt) {
        PlayerSortSettings settings = settings(player);
        boolean changed = service.sortContainer(inventory);
        if (settings.sortPlayerInventory()) {
            changed |= service.sortPlayerInventory(player.getInventory());
        }
        finish(player, settings, changed, feedbackAt);
    }

    private void finish(Player player, PlayerSortSettings settings, boolean changed, Location where) {
        if (changed) {
            service.feedback(player, settings, where);
        }
    }

    /** The block behind a container inventory (either half of a double chest), or null. */
    private org.bukkit.block.Block blockOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof BlockState state) {
            return state.getBlock();
        }
        Location loc = inventory.getLocation();
        return loc != null ? loc.getBlock() : null;
    }
}
