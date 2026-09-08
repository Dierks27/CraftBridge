package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.jei.GridLayout;
import com.dierks.craftbridge.jei.TransferEngine;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phantom inventory slots: while a Linked Workbench is open, the player's <em>empty</em>
 * inventory slots are shown (packet-only) holding one nearby-storage item type each, so
 * JEI's client believes the ingredients are in the inventory and sends its transfer.
 *
 * <ul>
 *   <li>Nothing here changes the real inventory; the server keeps those slots empty.</li>
 *   <li>JEI transfers that draw from a phantom slot pull the real items out of the
 *       recorded containers (nearest first) into the grid — see {@link #settle}.</li>
 *   <li>Any click on a phantom slot becomes "pull one real stack into this slot".</li>
 *   <li>After every change the snapshot is rebuilt and re-sent so counts stay honest;
 *       on session end {@code updateInventory()} clears the ghosts.</li>
 * </ul>
 * Only as many item types as there are empty slots (minus a small reserve JEI needs to
 * shuffle items) can be shown at once; the Combo Chest is the way to see everything.
 */
public final class PhantomManager {

    public static final NamespacedKey PHANTOM = Keys.key("phantom");

    /** One shown type: the item key (amount 1) and how many are in nearby storage. */
    public record Phantom(ItemStack key, int available) {
    }

    private static final class Session {
        final Map<Integer, Phantom> byRaw = new LinkedHashMap<>();
        List<StorageScanner.Source> sources = List.of();
    }

    private final CraftBridgePlugin plugin;
    private final WorkbenchFeature feature;
    private final SlotPackets packets;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public PhantomManager(CraftBridgePlugin plugin, WorkbenchFeature feature, SlotPackets packets) {
        this.plugin = plugin;
        this.feature = feature;
        this.packets = packets;
    }

    /** Null if the NMS packet bridge failed to load or the config switch is off. */
    public static PhantomManager create(CraftBridgePlugin plugin, WorkbenchFeature feature) {
        if (!plugin.config().workbenchPhantomSlots()) {
            return null;
        }
        try {
            SlotPackets packets = (SlotPackets) Class.forName("com.dierks.craftbridge.workbench.nms.PaperSlotPackets")
                    .getDeclaredConstructor().newInstance();
            return new PhantomManager(plugin, feature, packets);
        } catch (Throwable t) {
            plugin.getLogger().warning("Linked Workbench phantom slots disabled: the server-internals packet bridge failed to load ("
                    + t + "). JEI [+] will only see what the player carries.");
            return null;
        }
    }

    public boolean has(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean isPhantom(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.byRaw.containsKey(rawSlot);
    }

    public Phantom phantomAt(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? null : s.byRaw.get(rawSlot);
    }

    public List<StorageScanner.Source> sources(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? List.of() : s.sources;
    }

    /** Start (or restart) the phantom view for the player's open linked workbench. */
    public void start(Player player) {
        sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        rebuild(player);
    }

    /** Schedule a rebuild for next tick (after the current click/craft has been applied). */
    public void rebuildLater(Player player) {
        if (!has(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> rebuild(player));
    }

    /**
     * Re-scan storage, re-assign phantoms to the currently empty inventory slots and send
     * the packets: real content for slots that lost their phantom, phantoms for the rest.
     */
    public void rebuild(Player player) {
        Session session = sessions.get(player.getUniqueId());
        LinkedSession linked = feature.sessions().of(player);
        if (session == null || linked == null || !player.isOnline()) {
            return;
        }
        InventoryView view = player.getOpenInventory();
        if (view != linked.view() || view.getType() != InventoryType.WORKBENCH) {
            return;
        }
        session.sources = feature.scanner().scan(player, linked.record().location(), plugin.config().workbenchRadius());
        Map<ItemStack, Integer> totals = feature.scanner().aggregate(session.sources);
        List<Map.Entry<ItemStack, Integer>> types = new ArrayList<>(totals.entrySet());
        types.sort(Comparator.<Map.Entry<ItemStack, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().getType().name()));

        GridLayout layout = GridLayout.WORKBENCH;
        List<Integer> empties = new ArrayList<>();
        for (int raw : layout.inventorySlots()) {
            if (Items.isEmpty(view.getItem(raw))) {
                empties.add(raw);
            }
        }
        int usable = Math.max(0, empties.size() - plugin.config().workbenchPhantomReserve());
        Map<Integer, Phantom> next = new LinkedHashMap<>();
        for (int i = 0; i < usable && i < types.size(); i++) {
            Map.Entry<ItemStack, Integer> e = types.get(i);
            next.put(empties.get(i), new Phantom(e.getKey(), e.getValue()));
        }

        for (int raw : session.byRaw.keySet()) {
            if (!next.containsKey(raw)) {
                packets.sendRealSlot(player, raw);
            }
        }
        session.byRaw.clear();
        session.byRaw.putAll(next);
        for (Map.Entry<Integer, Phantom> e : next.entrySet()) {
            packets.sendSlot(player, e.getKey(), display(e.getValue()));
        }
    }

    /** End the phantom view; {@code resync} re-sends the real inventory so no ghosts remain. */
    public void end(Player player, boolean resync) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (resync && player.isOnline()) {
            player.updateInventory();
        }
    }

    /** A click on a phantom slot: move one real stack of that type from storage into the slot. */
    public int pullIntoSlot(Player player, int rawSlot) {
        Session session = sessions.get(player.getUniqueId());
        LinkedSession linked = feature.sessions().of(player);
        Phantom phantom = session == null ? null : session.byRaw.get(rawSlot);
        if (phantom == null || linked == null) {
            return 0;
        }
        InventoryView view = linked.view();
        if (!Items.isEmpty(view.getItem(rawSlot))) {
            rebuild(player);
            return 0;
        }
        int wanted = Math.max(1, Math.min(phantom.key().getMaxStackSize(), 64));
        int got = 0;
        for (StorageScanner.Pulled p : feature.scanner().pull(session.sources, phantom.key(), wanted)) {
            got += p.stack().getAmount();
        }
        if (got > 0) {
            view.setItem(rawSlot, phantom.key().asQuantity(got));
        }
        rebuild(player);
        return got;
    }

    // ---- JEI transfer integration ---------------------------------------------------

    /** What the JEI transfer engine should believe the phantom slots hold. */
    public Map<Integer, TransferEngine.Stack<ItemStack>> virtualSlots(Player player, InventoryView view) {
        Session session = sessions.get(player.getUniqueId());
        LinkedSession linked = feature.sessions().of(player);
        if (session == null || linked == null || linked.view() != view) {
            return Map.of();
        }
        Map<Integer, TransferEngine.Stack<ItemStack>> out = new HashMap<>();
        session.byRaw.forEach((raw, p) -> out.put(raw, new TransferEngine.Stack<>(p.key(), p.available())));
        return out;
    }

    /**
     * After the engine ran: for every phantom slot whose count went down, pull that many
     * real items out of storage (nearest first) and record their origins on the grid; for
     * every phantom slot whose count went up (the engine "stowed" items there), put those
     * items back into storage. Returns, per item key, how many items could NOT be pulled
     * so the caller can trim the grid accordingly.
     */
    public Map<ItemStack, Integer> settle(Player player, InventoryView view, GridLayout layout,
                                          Map<Integer, TransferEngine.Stack<ItemStack>> virtualBefore,
                                          Map<Integer, TransferEngine.Stack<ItemStack>> resultSlots) {
        Session session = sessions.get(player.getUniqueId());
        LinkedSession linked = feature.sessions().of(player);
        Map<ItemStack, Integer> deficits = new HashMap<>();
        if (session == null || linked == null) {
            return deficits;
        }
        for (Map.Entry<Integer, TransferEngine.Stack<ItemStack>> e : virtualBefore.entrySet()) {
            TransferEngine.Stack<ItemStack> before = e.getValue();
            TransferEngine.Stack<ItemStack> after = resultSlots.get(e.getKey());
            int afterCount = after == null ? 0 : after.count();
            int delta = before.count() - afterCount;
            if (delta > 0) {
                List<StorageScanner.Pulled> pulled = feature.scanner().pull(session.sources, before.key(), delta);
                int got = 0;
                for (StorageScanner.Pulled p : pulled) {
                    got += p.stack().getAmount();
                }
                if (got < delta) {
                    deficits.merge(before.key(), delta - got, Integer::sum);
                }
                recordOrigins(linked, layout, resultSlots, before.key(), pulled);
            } else if (delta < 0) {
                ItemStack back = before.key().asQuantity(-delta);
                ItemStack left = feature.scanner().deposit(session.sources, back);
                if (!Items.isEmpty(left)) {
                    for (ItemStack rest : player.getInventory().addItem(left).values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        }
        return deficits;
    }

    /** Spread the pulled amounts over the grid slots that now hold this item, in order. */
    private static void recordOrigins(LinkedSession linked, GridLayout layout,
                                      Map<Integer, TransferEngine.Stack<ItemStack>> resultSlots,
                                      ItemStack key, List<StorageScanner.Pulled> pulled) {
        List<Integer> gridRaws = new ArrayList<>();
        for (int raw : layout.gridSlots()) {
            TransferEngine.Stack<ItemStack> s = resultSlots.get(raw);
            if (s != null && s.key().isSimilar(key)) {
                gridRaws.add(raw);
            }
        }
        if (gridRaws.isEmpty()) {
            return;
        }
        int i = 0;
        for (StorageScanner.Pulled p : pulled) {
            int raw = gridRaws.get(i % gridRaws.size());
            linked.addOrigin(layout.gridIndex(raw), p.source().location(), p.stack().getAmount());
            i++;
        }
    }

    /** The client-side item: the real type with a lore line and a marker tag. */
    static ItemStack display(Phantom phantom) {
        ItemStack item = phantom.key().clone();
        item.setAmount(Math.max(1, Math.min(phantom.available(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(Text.item("<aqua>From nearby storage <gray>(" + phantom.available() + " available)"));
            lore.add(Text.item("<dark_gray>Click to take a stack"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PHANTOM, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
