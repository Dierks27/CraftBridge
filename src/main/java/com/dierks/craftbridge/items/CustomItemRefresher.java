package com.dierks.craftbridge.items;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.gui.Menu;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gives custom items made before 0.15 their model tag as players come across them, so a
 * resource pack can draw them without an admin migrating anything.
 *
 * <p>The tag is look only ({@link ModelTags}) and recipes accept both shapes meanwhile
 * ({@link CustomItemChoice}), so nothing breaks while an item is still untagged. Tagging them
 * is what makes an old item look like a new one and stack with it again. Three moments cover
 * almost everything a player can hold:
 * <ul>
 *   <li><b>join</b>: the whole inventory (armour and offhand included) and the ender chest;</li>
 *   <li><b>opening an inventory</b>: the player's own, and the container if it is a real one in
 *       the world. Never a CraftBridge GUI or another plugin's virtual inventory: their
 *       contents are buttons, and changing one could break how that plugin recognises a click;</li>
 *   <li><b>picking an old item up</b>: the inventory on the next tick. Not the item entity in
 *       the pickup event itself: Paper's {@code ItemEntity#playerTouch} keeps its own reference
 *       to the old stack for {@code inventory.add}, so replacing the entity's stack mid-pickup
 *       duplicates items on a partial pickup.</li>
 * </ul>
 * A {@code /craftbridge reload} rebuilds this listener; {@link #refreshOnline} covers the
 * players who were online through it.
 */
public final class CustomItemRefresher implements Listener {

    private final CraftBridgePlugin plugin;
    private final CustomItemRegistry registry;
    /** Players with an inventory refresh queued for the next tick, so a pickup burst queues one. */
    private final Set<UUID> queued = new HashSet<>();

    public CustomItemRefresher(CraftBridgePlugin plugin, CustomItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayer(event.getPlayer(), "joined");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (registry.isEmpty()) {
            return;
        }
        HumanEntity viewer = event.getPlayer();
        Inventory top = event.getInventory();
        int changed = isWorldInventory(top, viewer) ? refresh(top) : 0;
        changed += refresh(viewer.getInventory());
        report(changed, viewer.getName() + " opened " + top.getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (registry.isEmpty() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!registry.needsRefresh(event.getItem().getItemStack()) || !queued.add(player.getUniqueId())) {
            return;
        }
        // Next tick, once the pickup has landed in the inventory (see the class javadoc for why
        // the item entity itself is not touched).
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            queued.remove(player.getUniqueId());
            if (player.isOnline()) {
                report(refresh(player.getInventory()), player.getName() + " picked one up");
            }
        });
    }

    /** Every online player's inventory and ender chest; for players online through a reload. */
    public void refreshOnline() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player, "was online through a reload");
        }
    }

    private void refreshPlayer(Player player, String why) {
        if (registry.isEmpty()) {
            return;
        }
        int changed = refresh(player.getInventory()) + refresh(player.getEnderChest());
        report(changed, player.getName() + " " + why);
    }

    /**
     * A container that exists in the world (a chest, a barrel, a furnace, a donkey's pack) or
     * the viewer's own ender chest. A plugin GUI has no location: {@code Bukkit.createInventory}
     * inventories never do. CraftBridge's own menus are ruled out by their holder as well, in
     * case one is ever given a location.
     */
    private static boolean isWorldInventory(Inventory inventory, HumanEntity viewer) {
        if (inventory.getHolder(false) instanceof Menu) {
            return false;
        }
        return inventory.getLocation() != null || inventory.equals(viewer.getEnderChest());
    }

    /**
     * Refresh every slot; returns how many stacks changed. {@code getItem} may hand back a
     * live mirror, but {@code setItem} is what marks the container dirty and syncs the client,
     * so a changed stack is always written back.
     */
    private int refresh(Inventory inventory) {
        int changed = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && registry.refresh(stack)) {
                inventory.setItem(i, stack);
                changed++;
            }
        }
        return changed;
    }

    private void report(int changed, String context) {
        if (changed > 0) {
            plugin.debug("Gave " + changed + " custom item stack(s) made before 0.15 their model tag ("
                    + context + ").");
        }
    }
}
