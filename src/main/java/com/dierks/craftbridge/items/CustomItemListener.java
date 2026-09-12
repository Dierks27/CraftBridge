package com.dierks.craftbridge.items;

import com.dierks.craftbridge.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps player-head custom items out of the world and off players' heads.
 *
 * <p>A head-based custom item is a {@code PLAYER_HEAD}, so vanilla will happily let it be
 * placed as a block or worn in the helmet slot. Both lose the item's identity: a placed skull
 * keeps its texture but the {@code cb_item} stamp only survives on the block entity, and a
 * worn head is a texture with no way back to the definition. Neither is what an admin means
 * when they define a custom item, so both are refused.
 *
 * <p>HomeCraftManagement has its own head handling ({@code MiniHeadListener}), but it is a
 * separate plugin and a separate jar, and what it does is the opposite of this — it
 * <em>records</em> placed Minis rather than preventing placement. There is nothing to reuse,
 * and the two never see each other's items: HCM keys off {@code homecraftmanagement:mini_id}
 * and this keys off {@code craftbridge:cb_item}, so an item carrying one is ignored by the
 * other's listeners.
 */
public final class CustomItemListener implements Listener {

    private final CustomItemRegistry registry;

    public CustomItemListener(CustomItemRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        if (!registry.isHeadItem(inHand)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Text.msg("<red>That is a collectible item, not a block."));
    }

    /** Right-clicking a head onto a block is a place; catch the interact too for skull-on-wall. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        if (registry.isHeadItem(event.getItem())) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    /**
     * Block equipping a stamped head. Covers the three ways an item reaches the helmet slot:
     * right-clicking with it in hand, shift-clicking it from the inventory, and clicking or
     * number-key swapping it directly into the armour slot.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEquipByUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        // Only a right-click in the air (or on a non-interactive block) equips armour.
        if (registry.isHeadItem(event.getItem()) && event.getClickedBlock() == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEquipByClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && event.getClick() == ClickType.CREATIVE) {
            return;
        }
        if (isHelmetSlot(event) && registry.isHeadItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && isHelmetSlot(event)) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (registry.isHeadItem(hotbar)) {
                event.setCancelled(true);
                return;
            }
        }
        boolean shiftMove = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (shiftMove && registry.isHeadItem(event.getCurrentItem())
                && event.getClickedInventory() == player.getInventory()) {
            // Shift-clicking a wearable head from the inventory equips it; anything else is fine.
            if (player.getInventory().getHelmet() == null) {
                event.setCancelled(true);
            }
        }
    }

    private static boolean isHelmetSlot(InventoryClickEvent event) {
        return event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR
                && event.getSlot() == 39;
    }

    /** True when a material would be worn rather than held. Kept for readability at call sites. */
    static boolean isWearable(Material material) {
        return material == Material.PLAYER_HEAD;
    }
}
