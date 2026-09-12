package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.jei.GridLayout;
import com.dierks.craftbridge.jei.TransferEngine;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 *   <li><b>The snapshot itself never changes the inventory.</b> Phantoms are packet-only:
 *       the server keeps those slots empty, so they are always free to receive something.</li>
 *   <li><b>Clicking one takes items for real</b>, bounded by {@link PullPlanner}: left-click
 *       puts a stack straight on the cursor, right-click half a stack, and shift-click fills
 *       the player's empty slots (shift-click has no cursor semantics). One click, with the
 *       slot's true contents and the cursor sent in the same tick. Everything else on a phantom
 *       (number keys, drops, double-click collect, offhand swaps) is cancelled and the slot
 *       re-sent. Putting an item <em>into</em> a phantom slot is ordinary vanilla behaviour
 *       and is always allowed — the slot really is empty.</li>
 *   <li><b>Paged.</b> A crafting menu has exactly 36 player-inventory slots and JEI only
 *       reads those, so at most that many types can be visible at once (fewer, because
 *       occupied slots and the reserve do not count). The rest live on further pages,
 *       ordered so page one is almost always the right one.</li>
 *   <li>Only genuinely <b>empty</b> slots are ever used, and only those slots are locked. The
 *       player's real items, the crafting grid and the result behave exactly as at a vanilla
 *       table — items can be placed by hand and crafted normally. With no free slot at all
 *       nothing is shown and the workbench is a vanilla crafting table.</li>
 * </ul>
 */
public final class PhantomManager {

    public static final NamespacedKey PHANTOM = Keys.key("phantom");

    /** Icon for the page buttons: a barrier is an ingredient in no recipe, so JEI ignores it. */
    private static final Material BUTTON = Material.BARRIER;

    /** One shown type: the item key (amount 1) and how many are in nearby storage. */
    public record Phantom(ItemStack key, int available) {
    }

    /** A page button drawn in an empty inventory slot. */
    public enum Button {
        PREVIOUS(-1), NEXT(1);

        private final int delta;

        Button(int delta) {
            this.delta = delta;
        }
    }

    private static final class Session {
        final Map<Integer, Phantom> byRaw = new LinkedHashMap<>();
        final Map<Integer, Button> buttons = new LinkedHashMap<>();
        List<StorageScanner.Source> sources = List.of();
        Map<Material, Integer> weights = Map.of();
        int page;
        int pages = 1;
        int types;
        String lastStatus = "";
        /** Set when the client's view was wiped (a cancelled click resyncs it) so the next rebuild re-sends everything. */
        boolean resendAll;
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

    /** True for an item phantom (not a page button). */
    public boolean isPhantom(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.byRaw.containsKey(rawSlot);
    }

    /** True for anything CraftBridge drew into that slot: an item phantom or a page button. */
    public boolean isPhantomSlot(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && (s.byRaw.containsKey(rawSlot) || s.buttons.containsKey(rawSlot));
    }

    public Phantom phantomAt(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? null : s.byRaw.get(rawSlot);
    }

    public Button buttonAt(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? null : s.buttons.get(rawSlot);
    }

    /** What the click handler needs to know about a slot: phantom, page button, or real. */
    public WorkbenchClicks.Slot slotKind(Player player, int rawSlot) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return WorkbenchClicks.Slot.REAL;
        }
        if (s.byRaw.containsKey(rawSlot)) {
            return WorkbenchClicks.Slot.PHANTOM;
        }
        return s.buttons.containsKey(rawSlot) ? WorkbenchClicks.Slot.BUTTON : WorkbenchClicks.Slot.REAL;
    }

    /**
     * Take items out of storage into the player's inventory: what clicking a phantom slot
     * does. The amount is bounded by {@link PullPlanner} — by what storage actually holds and
     * by the empty slots the player actually has — so this can neither overflow nor need to
     * drop anything, which is what made the v0.2 version of this unsafe.
     *
     * <p>Storage is re-scanned first, so a container emptied or locked since the snapshot is
     * handled by moving what is really there rather than by trusting a stale count.
     *
     * @return how many items were moved
     */
    public int pull(Player player, int rawSlot, PullPlanner.Mode mode) {
        Session session = sessions.get(player.getUniqueId());
        LinkedSession linked = feature.sessions().of(player);
        Phantom phantom = session == null ? null : session.byRaw.get(rawSlot);
        if (phantom == null || linked == null || !player.isOnline()) {
            return 0;
        }
        InventoryView view = linked.view();
        boolean toCursor = mode != PullPlanner.Mode.ALL;
        if (!Items.isEmpty(view.getItem(rawSlot))
                || (toCursor && !Items.isEmpty(player.getItemOnCursor()))) {
            // Something real landed in the slot, or the player picked something up in the
            // meantime — in which case this click was a place, not a pull.
            rebuildNow(player, rawSlot);
            return 0;
        }
        session.sources = feature.scanner().scan(player, linked.record().location(), plugin.config().workbenchRadius());
        // Shared with the client mod's storage panel, so a click means the same thing either way.
        StoragePull.Result result = StoragePull.pull(player, feature.scanner(), session.sources, phantom.key(),
                mode, StoragePull.freeInventorySlots(view), over -> putBack(player, session, over));
        rebuildNow(player, rawSlot);
        return result.moved();
    }

    /**
     * Tell the client the truth about the slot it just pulled from, in this tick, before any
     * scheduled rebuild. Without this the client keeps drawing the phantom it already took,
     * and the next click is spent re-syncing the slot instead of doing what the player meant.
     */
    private void rebuildNow(Player player, int rawSlot) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.byRaw.remove(rawSlot);
            session.resendAll = true;
        }
        packets.sendRealSlot(player, rawSlot);
        rebuildLater(player, true);
    }

    /** Anything that would not fit goes back where it came from, never on the floor. */
    private void putBack(Player player, Session session, ItemStack stack) {
        ItemStack left = feature.scanner().deposit(session.sources, stack);
        if (Items.isEmpty(left)) {
            return;
        }
        plugin.getLogger().warning("Linked Workbench: " + Items.describe(left) + " x" + left.getAmount()
                + " fit neither " + player.getName() + "'s inventory nor nearby storage; dropping it at their feet.");
        player.getWorld().dropItemNaturally(player.getLocation(), left);
    }

    public List<StorageScanner.Source> sources(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s == null ? List.of() : s.sources;
    }

    /**
     * True when this player's own client is showing them what is in storage, so faking items
     * into their inventory slots would only show everything twice.
     */
    private boolean clientHandlesIt(Player player) {
        com.dierks.craftbridge.link.LinkFeature link = plugin.feature(com.dierks.craftbridge.link.LinkFeature.class);
        return link != null && link.handlesStorageItself(player);
    }

    /** Start (or restart) the phantom view for the player's open linked workbench. */
    public void start(Player player) {
        if (clientHandlesIt(player)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        session.page = 0;
        session.weights = feature.ingredientIndex().weights(player);
        rebuild(player);
    }

    /** Schedule a rebuild for next tick (after the current click/craft has been applied). */
    public void rebuildLater(Player player) {
        rebuildLater(player, false);
    }

    /**
     * @param force re-send every phantom rather than only the changed ones. Needed after any
     *              cancelled click, because CraftBridge cancelling a click makes CraftBukkit
     *              re-sync the whole container from the server's truth — which has no phantoms
     *              in it at all, so the client's copy of them is gone.
     */
    public void rebuildLater(Player player, boolean force) {
        if (!has(player)) {
            return;
        }
        if (force) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null) {
                session.resendAll = true;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> rebuild(player));
    }

    /** Turn the page (a page button, or {@code /craftbridge page}). Returns false if there is only one. */
    public boolean turnPage(Player player, int delta) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.pages <= 1) {
            return false;
        }
        session.page = Math.floorMod(session.page + delta, session.pages);
        rebuild(player);
        return true;
    }

    public boolean turnPage(Player player, Button button) {
        return turnPage(player, button.delta);
    }

    /**
     * Note that the client's copy of the phantoms is about to be wiped — cancelling a click
     * makes CraftBukkit re-sync the container from the server's truth, which has none in it —
     * so the next rebuild sends them all again rather than only the changed ones.
     */
    public void markResendAll(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.resendAll = true;
        }
    }

    /** Re-send one phantom slot exactly as it was: the answer to any click on it. */
    public void resend(Player player, int rawSlot) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Phantom phantom = session.byRaw.get(rawSlot);
        if (phantom != null) {
            packets.sendSlot(player, rawSlot, display(phantom));
            return;
        }
        Button button = session.buttons.get(rawSlot);
        if (button != null) {
            packets.sendSlot(player, rawSlot, buttonItem(button, session));
        }
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
        if (clientHandlesIt(player)) {
            end(player, true); // the mod arrived mid-session: hand the slots back to the client
            return;
        }
        InventoryView view = player.getOpenInventory();
        if (view != linked.view() || view.getType() != InventoryType.WORKBENCH) {
            return;
        }
        if (!Items.isEmpty(view.getCursor())) {
            // The player is holding an item: sending slot packets now would fight the client's
            // own prediction and make hand-placing items into the grid look like it failed.
            // The next click rebuilds anyway, so there is nothing to schedule.
            return;
        }
        session.sources = feature.scanner().scan(player, linked.record().location(), plugin.config().workbenchRadius());
        List<Map.Entry<ItemStack, Integer>> types = sortedTypes(session);
        session.types = types.size();

        GridLayout layout = GridLayout.WORKBENCH;
        List<Integer> empties = new ArrayList<>();
        for (int raw : layout.inventorySlots()) {
            if (Items.isEmpty(view.getItem(raw))) {
                empties.add(raw);
            }
        }

        Map<Integer, Phantom> nextPhantoms = new LinkedHashMap<>();
        Map<Integer, Button> nextButtons = new LinkedHashMap<>();
        int usable = Math.max(0, empties.size() - plugin.config().workbenchPhantomReserve());
        if (empties.isEmpty()) {
            session.pages = 1;
            session.page = 0;
            status(player, session, "<yellow>Nearby storage hidden <gray>— no free inventory slots.");
        } else if (usable == 0 || types.isEmpty()) {
            session.pages = 1;
            session.page = 0;
            status(player, session, types.isEmpty()
                    ? "<yellow>No storage in range."
                    : "<yellow>Nearby storage hidden <gray>— no free inventory slots.");
        } else {
            // Page buttons live in two of the free slots, but only when they buy something.
            int perPage = usable;
            int pages = ceilDiv(types.size(), perPage);
            boolean buttons = pages > 1 && usable >= 3;
            if (buttons) {
                perPage = usable - 2;
                pages = ceilDiv(types.size(), perPage);
            }
            session.pages = Math.max(1, pages);
            session.page = Math.floorMod(session.page, session.pages);

            int from = session.page * perPage;
            for (int i = 0; i < perPage && from + i < types.size(); i++) {
                Map.Entry<ItemStack, Integer> e = types.get(from + i);
                nextPhantoms.put(empties.get(i), new Phantom(e.getKey(), e.getValue()));
            }
            if (buttons) {
                nextButtons.put(empties.get(usable - 2), Button.PREVIOUS);
                nextButtons.put(empties.get(usable - 1), Button.NEXT);
            }
            status(player, session, "<aqua>Nearby storage <gray>— page <white>" + (session.page + 1)
                    + "<gray>/<white>" + session.pages + " <gray>(" + types.size() + " types)");
        }

        // Send only what actually changed. Re-sending every phantom on every click floods the
        // client with slot packets, which is what made hand-placing items feel broken.
        for (int raw : session.byRaw.keySet()) {
            if (!nextPhantoms.containsKey(raw) && !nextButtons.containsKey(raw)) {
                packets.sendRealSlot(player, raw);
            }
        }
        for (int raw : session.buttons.keySet()) {
            if (!nextPhantoms.containsKey(raw) && !nextButtons.containsKey(raw)) {
                packets.sendRealSlot(player, raw);
            }
        }
        boolean pageChanged = !session.buttons.equals(nextButtons);
        boolean resendAll = session.resendAll;
        session.resendAll = false;
        Map<Integer, Phantom> previous = resendAll ? new LinkedHashMap<>() : new LinkedHashMap<>(session.byRaw);
        session.byRaw.clear();
        session.byRaw.putAll(nextPhantoms);
        session.buttons.clear();
        session.buttons.putAll(nextButtons);
        for (Map.Entry<Integer, Phantom> e : nextPhantoms.entrySet()) {
            Phantom before = previous.get(e.getKey());
            Phantom now = e.getValue();
            if (before != null && before.available() == now.available() && before.key().isSimilar(now.key())) {
                continue; // unchanged: the client already shows exactly this
            }
            packets.sendSlot(player, e.getKey(), display(now));
        }
        for (Map.Entry<Integer, Button> e : nextButtons.entrySet()) {
            if (resendAll || pageChanged || !previous.isEmpty()) {
                packets.sendSlot(player, e.getKey(), buttonItem(e.getValue(), session));
            }
        }
    }

    /**
     * Storage types in the order that makes page one worth having: ingredients of recipes
     * the player has unlocked first (most-used first), then plain count, then by name.
     */
    private List<Map.Entry<ItemStack, Integer>> sortedTypes(Session session) {
        Map<ItemStack, Integer> totals = feature.scanner().aggregate(session.sources);
        List<Map.Entry<ItemStack, Integer>> types = new ArrayList<>(totals.entrySet());
        Map<Material, Integer> weights = session.weights;
        types.sort(Comparator
                .comparingInt((Map.Entry<ItemStack, Integer> e) -> -weights.getOrDefault(e.getKey().getType(), 0))
                .thenComparing(Comparator.comparingInt((Map.Entry<ItemStack, Integer> e) -> -e.getValue()))
                .thenComparing(e -> e.getKey().getType().name()));
        return types;
    }

    private static int ceilDiv(int a, int b) {
        return b <= 0 ? 1 : (a + b - 1) / b;
    }

    /** One action-bar line, only when it actually changed. */
    private void status(Player player, Session session, String message) {
        if (message.equals(session.lastStatus)) {
            return;
        }
        session.lastStatus = message;
        player.sendActionBar(Text.mm(message));
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
            linked.addOrigin(layout.gridIndex(raw), p.source().location(), p.stack().getAmount(), p.stack());
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
            lore.add(Text.item("<dark_gray>Shown for JEI [+]; take it from a Combo Chest"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PHANTOM, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The client-side page button. */
    private static ItemStack buttonItem(Button button, Session session) {
        ItemStack item = new ItemStack(BUTTON);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(button == Button.PREVIOUS
                    ? "<yellow>◀ Previous page" : "<yellow>Next page ▶"));
            meta.lore(Text.lore(List.of(
                    "<gray>Nearby storage, page <white>" + (session.page + 1) + "<gray>/<white>" + session.pages,
                    "<gray>" + session.types + " item types in range",
                    "",
                    "<yellow>Click <gray>to turn the page",
                    "<dark_gray>or /craftbridge page next|prev")));
            meta.getPersistentDataContainer().set(PHANTOM, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }
}
