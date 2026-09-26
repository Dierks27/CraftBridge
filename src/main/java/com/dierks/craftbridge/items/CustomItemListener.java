package com.dierks.craftbridge.items;

import com.dierks.craftbridge.util.Text;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Cake;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.util.function.BooleanSupplier;

/**
 * Keeps custom items from being placed as the plain block they are built on, and player-head
 * custom items off players' heads.
 *
 * <p>A head-based custom item is a {@code PLAYER_HEAD}, so vanilla will happily let it be
 * placed as a block or worn in the helmet slot. Both lose the item's identity: a placed skull
 * keeps its texture but the {@code cb_item} stamp only survives on the block entity, and a
 * worn head is a texture with no way back to the definition. Neither is what an admin means
 * when they define a custom item, so both are always refused.
 *
 * <p>Any other custom item built on something placeable (cobblestone, string, seeds) has the
 * same problem without the texture: placed, it is the plain block, and breaking it drops the
 * plain item. The custom item is simply gone, and with a pack giving it its own look (0.15)
 * it would also change appearance the moment it lands. Placing those is refused too, and so
 * is potting one or putting one on a cake as a candle, unless the admin sets
 * {@code custom-items.placeable: true} — for an item that is meant to be a decorative block
 * and is fine ending up as the vanilla one. Custom tools keep working: Paper reports a hoe
 * tilling or an axe stripping as a block place too, so only block items are refused. Only
 * defined, stamped items are affected: the Linked Workbench and Combo Chest place-items carry
 * their own tag, not {@code cb_item}, and place as always.
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
    /** {@code custom-items.placeable}, read from the live config on each place rather than cached. */
    private final BooleanSupplier placeable;

    public CustomItemListener(CustomItemRegistry registry, BooleanSupplier placeable) {
        this.registry = registry;
        this.placeable = placeable;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // An orphaned stamp (definition deleted) is an ordinary item to CraftBridge; it places.
        CustomItemDef def = registry.get(CustomItemRegistry.idOf(event.getItemInHand()));
        if (def == null) {
            return;
        }
        if (def.base() == Material.PLAYER_HEAD) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.msg("<red>That is a collectible item, not a block."));
            return;
        }
        // Paper also fires this event for what a tool does to a block (a hoe tilling, an axe
        // stripping, flint and steel lighting), with the tool as the item in hand. Only an item
        // that is itself a block item turns into the block. The stack, not the definition: an
        // admin may have moved the item to another base since this stack was made.
        if (placesABlock(event.getItemInHand().getType()) && !placeable.getAsBoolean()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.msg(REFUSED));
        }
    }

    private static final String REFUSED = "<red>That is a custom item: placed, it would turn into the plain block.";

    /**
     * True when the material's item is a block item: cobblestone, but also string, seeds and
     * carrots, which place a block of another name. False for tools, flint and steel and the like.
     */
    static boolean placesABlock(Material base) {
        ItemType type = base.asItemType();
        return type != null && type.hasBlockType();
    }

    /** Potting a plant never fires a place event, but turns the item into the plain pot contents all the same. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPot(PlayerFlowerPotManipulateEvent event) {
        if (event.isPlacing() && refuses(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.msg(REFUSED));
        }
    }

    /** Nor does putting a candle on a cake, which leaves a candle cake that drops a plain candle. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCandleOnCake(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.CAKE
                || !(block.getBlockData() instanceof Cake cake) || cake.getBites() != 0) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && Tag.CANDLES.isTagged(item.getType()) && refuses(item)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.getPlayer().sendMessage(Text.msg(REFUSED));
        }
    }

    /** A defined custom item that may not become a block, going by custom-items.placeable. */
    private boolean refuses(ItemStack item) {
        CustomItemDef def = registry.get(CustomItemRegistry.idOf(item));
        return def != null && def.base() != Material.PLAYER_HEAD && !placeable.getAsBoolean();
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
