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
                plugin.config().sortFeedbackDefault(),
                plugin.config().sortMiddleClickAllowed() && plugin.config().sortMiddleClickDefault()));
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
        // The client mod only takes a middle-click while the server says it will sort; tell it
        // now rather than at the next join.
        com.dierks.craftbridge.link.LinkFeature link = plugin.feature(com.dierks.craftbridge.link.LinkFeature.class);
        if (link != null) {
            link.refreshHello(player);
        }
    }

    /** Whether middle-click sorting is offered on this server at all. */
    public boolean middleClickAllowed() {
        return plugin.config().sortMiddleClickAllowed();
    }

    /** Whether this player's client should take a middle-click as a sort: allowed, permitted, and wanted. */
    public boolean middleClickOffered(Player player) {
        return middleClickAllowed() && player.hasPermission("craftbridge.sort") && settings(player).middleClick();
    }

    /** What a middle-click sort did: whether a sort ran, and what to tell the player (may be empty). */
    public record ClientSort(boolean sorted, String message) {
        static ClientSort silent() {
            return new ClientSort(false, "");
        }
    }

    /**
     * A middle-click from the CraftBridge-Client mod, over the open container's slots or over
     * the player's own. {@code /sort}'s rules on whatever the player has open right now; the
     * caller has already checked the menu id is the one the client clicked in.
     *
     * <p>Silent where nothing can be sorted (a Linked Workbench, a CraftBridge menu, a furnace):
     * players middle-click out of habit, and a chat line for each would be noise. A refusal the
     * player can act on (locked, no permission, inventory sorting off) says why.
     */
    public ClientSort sortFromClient(Player player, boolean playerSide) {
        if (!middleClickAllowed() || !settings(player).middleClick()) {
            return ClientSort.silent(); // a stale flag; the refreshed hello is on its way
        }
        if (!player.hasPermission("craftbridge.sort")) {
            return new ClientSort(false, "You may not sort containers.");
        }
        InventoryView view = player.getOpenInventory();
        Inventory top = view.getTopInventory();
        MiddleClickRules.Screen screen;
        if (top.getHolder(false) instanceof Menu) {
            screen = MiddleClickRules.Screen.CRAFTBRIDGE_MENU;
        } else if (service.isSortableContainer(top)) {
            screen = MiddleClickRules.Screen.SORTABLE_CONTAINER;
        } else if (view.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            // A Linked Workbench is WORKBENCH, not CRAFTING: its phantom-slot view is never sorted.
            screen = MiddleClickRules.Screen.OWN_INVENTORY;
        } else {
            screen = MiddleClickRules.Screen.OTHER;
        }
        PlayerSortSettings settings = settings(player);
        switch (MiddleClickRules.decide(screen, playerSide,
                com.dierks.craftbridge.util.Items.isEmpty(player.getItemOnCursor()), playerInventoryAllowed())) {
            case SORT_PLAYER -> {
                finish(player, settings, service.sortPlayerInventory(player.getInventory()), null);
                return new ClientSort(true, "");
            }
            case INVENTORY_SORTING_OFF -> {
                return new ClientSort(false, "Inventory sorting is turned off on this server.");
            }
            case SORT_CONTAINER -> {
                org.bukkit.block.Block block = blockOf(top);
                // Lock and claim of both halves of a double chest; no synthetic interact event
                // from a packet handler, since the player already has it open (as with /sort).
                if (block != null && !plugin.containerAccess().canUseAll(player, block, top, false)) {
                    return new ClientSort(false, "That container is locked.");
                }
                boolean changed = service.sortContainer(top);
                if (settings.sortPlayerInventory()) {
                    changed |= service.sortPlayerInventory(player.getInventory());
                }
                finish(player, settings, changed, top.getLocation());
                return new ClientSort(true, "");
            }
            default -> {
                return ClientSort.silent();
            }
        }
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
