package com.dierks.craftbridge.link;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import com.dierks.craftbridge.workbench.BlockKind;
import com.dierks.craftbridge.workbench.LinkedSession;
import com.dierks.craftbridge.workbench.PullPlanner;
import com.dierks.craftbridge.workbench.StoragePull;
import com.dierks.craftbridge.workbench.StorageScanner;
import com.dierks.craftbridge.workbench.WorkbenchFeature;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server half of the link to the CraftBridge-Client mod.
 *
 * <p>A server can only show JEI as much storage as fits in the 36 slots of the open crafting
 * menu, because that is where JEI looks. This feature removes that ceiling for players who
 * have the mod: it sends them what is in range directly, turns their phantom slots off
 * (they no longer need faked items), and fills the grid for them when they press JEI's [+].
 *
 * <p><b>The client is not trusted with anything.</b> Its request names a recipe and, for a
 * display with no registered recipe, what each slot would accept. Everything else — what the
 * player is carrying, what is in the chests, whether they may open those chests, how much may
 * be taken — is the server's own reading, done here at the moment of the request. The worst a
 * modified client can do is ask for a recipe it could have asked for by clicking.
 *
 * <p>Players without the mod are untouched: no hello arrives, nothing is sent to them, and
 * their phantom slots and {@code jei:recipe_transfer} path work exactly as before.
 */
public final class LinkFeature implements CraftBridgePlugin.Feature, PluginMessageListener, Listener {

    /** How often a live session's storage is re-read and any change sent on. */
    private static final int PUSH_TICKS = 20;
    /** Encoded item blobs are the same bytes every time; keep the recent ones rather than re-encoding. */
    private static final int BLOB_CACHE_LIMIT = 4096;

    private final CraftBridgePlugin plugin;
    private final Map<UUID, Linked> linked = new HashMap<>();
    private final Map<ItemStack, byte[]> blobCache = new LinkedHashMap<>();
    private ItemBlobs blobs;
    private int task = -1;

    /** One player who has the mod and has said hello. */
    private static final class Linked {
        final String modVersion;
        final SnapshotTracker sent = new SnapshotTracker();
        boolean sessionOpen;
        /**
         * Set once the client has confirmed it received a snapshot <em>and is showing it</em>.
         * Until then the player keeps their phantom slots: a handshake only proves the mod is
         * loaded, and a mod that cannot display what it was sent must leave the player with
         * the server's own view rather than with nothing at all.
         */
        boolean displaying;

        Linked(String modVersion) {
            this.modVersion = modVersion;
        }
    }

    public LinkFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "client-link";
    }

    @Override
    public void enable() {
        blobs = ItemBlobs.create(plugin);
        if (blobs == null) {
            return; // already logged; the plugin runs on without the link
        }
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : List.of(LinkProtocol.CHANNEL_HELLO, LinkProtocol.CHANNEL_RESYNC,
                LinkProtocol.CHANNEL_STORAGE_ACK, LinkProtocol.CHANNEL_PULL_REQUEST,
                LinkProtocol.CHANNEL_TRANSFER_REQUEST)) {
            messenger.registerIncomingPluginChannel(plugin, channel, this);
        }
        for (String channel : List.of(LinkProtocol.CHANNEL_HELLO, LinkProtocol.CHANNEL_STORAGE,
                LinkProtocol.CHANNEL_TRANSFER_RESULT, LinkProtocol.CHANNEL_SESSION_END,
                LinkProtocol.CHANNEL_ITEM_CATALOG)) {
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pushAll, PUSH_TICKS, PUSH_TICKS)
                .getTaskId();
        plugin.getLogger().info("CraftBridge client link: speaking protocol v" + LinkProtocol.VERSION
                + " on " + LinkProtocol.CHANNEL_HELLO + " (players without the mod are unaffected).");
    }

    @Override
    public void disable() {
        if (task != -1) {
            plugin.getServer().getScheduler().cancelTask(task);
            task = -1;
        }
        HandlerList.unregisterAll(this);
        for (UUID id : List.copyOf(linked.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                send(player, LinkProtocol.CHANNEL_SESSION_END,
                        LinkProtocol.encode(new LinkProtocol.SessionEnd("the server plugin is reloading")));
            }
        }
        linked.clear();
        blobCache.clear();
    }

    /**
     * True when this player's client is showing storage itself, so the server should not also
     * fake items into their inventory slots. The two mechanisms answer the same question and
     * running both would show every item twice.
     */
    public boolean handlesStorageItself(Player player) {
        Linked link = linked.get(player.getUniqueId());
        return link != null && link.displaying;
    }

    /** A Linked Workbench just opened: send the snapshot now rather than on the next poll. */
    public void sessionOpened(Player player) {
        push(player, true);
    }

    /** A Linked Workbench just closed: tell the client to drop its view of the storage. */
    public void sessionEnded(Player player, String reason) {
        Linked link = linked.get(player.getUniqueId());
        if (link == null || !link.sessionOpen) {
            return;
        }
        link.sessionOpen = false;
        send(player, LinkProtocol.CHANNEL_SESSION_END, LinkProtocol.encode(new LinkProtocol.SessionEnd(reason)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        linked.remove(event.getPlayer().getUniqueId());
    }

    // ---- incoming --------------------------------------------------------------------

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> onPluginMessageReceived(channel, player, message));
            return;
        }
        try {
            switch (channel) {
                case LinkProtocol.CHANNEL_HELLO -> onHello(player, LinkProtocol.decodeClientHello(message));
                case LinkProtocol.CHANNEL_RESYNC -> onResync(player);
                case LinkProtocol.CHANNEL_STORAGE_ACK ->
                        onStorageAck(player, LinkProtocol.decodeStorageAck(message));
                case LinkProtocol.CHANNEL_PULL_REQUEST ->
                        onPullRequest(player, LinkProtocol.decodePullRequest(message));
                case LinkProtocol.CHANNEL_TRANSFER_REQUEST ->
                        onTransferRequest(player, LinkProtocol.decodeTransferRequest(message));
                default -> {
                }
            }
        } catch (IllegalArgumentException versionMismatch) {
            // The two halves are from different releases. Say so once, to the person who can
            // fix it, rather than trying to guess what the payload meant.
            linked.remove(player.getUniqueId());
            player.sendMessage(Text.msg("<yellow>CraftBridge: " + versionMismatch.getMessage()));
            plugin.getLogger().info("CraftBridge client link: " + player.getName() + " on " + channel
                    + ": " + versionMismatch.getMessage());
        } catch (RuntimeException ex) {
            plugin.debug("CraftBridge client link: unreadable " + channel + " from " + player.getName()
                    + " (" + message.length + " bytes): " + ex);
        }
    }

    private void onHello(Player player, LinkProtocol.ClientHello hello) {
        linked.put(player.getUniqueId(), new Linked(hello.modVersion()));
        plugin.getLogger().info("CraftBridge client link: " + player.getName() + " has the mod (version "
                + hello.modVersion() + "); phantom slots stay on until it confirms it is showing storage.");
        // No FLAG_PHANTOM_SLOTS_OFF yet: the phantoms are still there, and stay there until the
        // client acknowledges a snapshot. Saying otherwise here is what would let a mod that
        // cannot display anything leave the player with nothing.
        send(player, LinkProtocol.CHANNEL_HELLO, LinkProtocol.encode(new LinkProtocol.ServerHello(
                plugin.getPluginMeta().getVersion(), 0)));
        sendCatalog(player);
        push(player, true);
    }

    private void onResync(Player player) {
        push(player, true);
    }

    /**
     * The client has a snapshot and says whether it is putting it in front of the player. Only
     * that second half earns the removal of their phantom slots.
     */
    private void onStorageAck(Player player, LinkProtocol.StorageAck ack) {
        Linked link = linked.get(player.getUniqueId());
        if (link == null || link.displaying == ack.displaying()) {
            return;
        }
        link.displaying = ack.displaying();
        plugin.getLogger().info("CraftBridge client link: " + player.getName() + " confirmed snapshot #"
                + ack.sequence() + (ack.displaying()
                ? " and is showing it; phantom slots off." : " but is not showing it; phantom slots stay on."));
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        if (workbench == null || workbench.phantoms() == null) {
            return;
        }
        if (ack.displaying()) {
            workbench.phantoms().end(player, true);
        } else {
            workbench.phantoms().start(player); // hand the view back
        }
    }

    // ---- storage ---------------------------------------------------------------------

    /**
     * Re-read every live session and send on what changed. A poll rather than an event: a
     * hopper filling a chest is not something the plugin is told about, and the phantom slots
     * this replaces re-scanned on every click for the same reason. The message that comes out
     * of it is a delta, so a quiet second costs nothing on the wire.
     */
    private void pushAll() {
        if (linked.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(linked.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                push(player, false);
            }
        }
    }

    private void push(Player player, boolean full) {
        Linked link = linked.get(player.getUniqueId());
        if (link == null || blobs == null) {
            return;
        }
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        LinkedSession session = workbench == null ? null : workbench.sessions().of(player);
        if (session == null) {
            if (link.sessionOpen) {
                link.sessionOpen = false;
                send(player, LinkProtocol.CHANNEL_SESSION_END,
                        LinkProtocol.encode(new LinkProtocol.SessionEnd("the workbench was closed")));
            }
            return;
        }

        Map<SnapshotTracker.ItemKey, Integer> current = new LinkedHashMap<>();
        for (Map.Entry<ItemStack, Integer> entry : inRange(workbench, player, session).entrySet()) {
            current.put(new SnapshotTracker.ItemKey(blob(entry.getKey())), entry.getValue());
        }

        if (full || !link.sessionOpen) {
            int sequence = link.sent.advanceTo(current);
            byte[] payload = LinkProtocol.encode(new LinkProtocol.Storage(sequence, true, entries(current)));
            send(player, LinkProtocol.CHANNEL_STORAGE, payload);
            link.sessionOpen = true;
            plugin.getLogger().info("CraftBridge client link: sent " + player.getName() + " a full snapshot #"
                    + sequence + " of " + current.size() + " item type(s), " + payload.length + " bytes.");
            return;
        }
        List<LinkProtocol.Entry> changes = link.sent.diff(current);
        if (changes.isEmpty()) {
            return;
        }
        int sequence = link.sent.advanceTo(current);
        byte[] payload = LinkProtocol.encode(new LinkProtocol.Storage(sequence, false, changes));
        send(player, LinkProtocol.CHANNEL_STORAGE, payload);
        plugin.debug("CraftBridge client link: sent " + player.getName() + " delta #" + sequence + " of "
                + changes.size() + " change(s), " + payload.length + " bytes.");
    }

    private Map<ItemStack, Integer> inRange(WorkbenchFeature workbench, Player player, LinkedSession session) {
        return workbench.scanner().aggregate(sources(workbench, player, session));
    }

    private List<StorageScanner.Source> sources(WorkbenchFeature workbench, Player player, LinkedSession session) {
        return workbench.scanner().scan(player, session.record().location(), plugin.config().workbenchRadius());
    }

    private static List<LinkProtocol.Entry> entries(Map<SnapshotTracker.ItemKey, Integer> counts) {
        List<LinkProtocol.Entry> out = new ArrayList<>(counts.size());
        counts.forEach((key, count) -> out.add(new LinkProtocol.Entry(key.bytes(), count)));
        return out;
    }

    private byte[] blob(ItemStack key) {
        byte[] cached = blobCache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] encoded = blobs.encode(key);
        if (blobCache.size() >= BLOB_CACHE_LIMIT) {
            blobCache.clear();
        }
        blobCache.put(key.clone(), encoded);
        return encoded;
    }

    // ---- the item catalog ------------------------------------------------------------

    /**
     * The items this server invented: CraftBridge's own blocks and every custom recipe's
     * result. They are renamed vanilla items carrying plugin data rather than registry
     * entries of their own, so JEI has no tile for them and nothing to look a recipe up
     * from until it is told they exist.
     */
    private void sendCatalog(Player player) {
        List<LinkProtocol.CatalogEntry> entries = new ArrayList<>();
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        if (workbench != null) {
            for (BlockKind kind : BlockKind.values()) {
                add(entries, workbench.items().placeItem(kind, 1));
            }
        }
        RecipeFeature recipes = plugin.feature(RecipeFeature.class);
        if (recipes != null) {
            for (CustomRecipe recipe : recipes.store().all().values()) {
                add(entries, recipe.result());
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        send(player, LinkProtocol.CHANNEL_ITEM_CATALOG,
                LinkProtocol.encode(new LinkProtocol.ItemCatalog(entries)));
        plugin.debug("CraftBridge client link: sent " + entries.size() + " custom item(s) to " + player.getName());
    }

    private void add(List<LinkProtocol.CatalogEntry> entries, ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return;
        }
        ItemStack one = stack.clone();
        one.setAmount(1);
        entries.add(new LinkProtocol.CatalogEntry(blob(one), Items.describe(one), List.of()));
    }

    // ---- transfers -------------------------------------------------------------------

    private void onTransferRequest(Player player, LinkProtocol.TransferRequest request) {
        Linked link = linked.get(player.getUniqueId());
        if (link == null) {
            return; // never said hello: nothing to answer to
        }
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        LinkedSession session = workbench == null ? null : workbench.sessions().of(player);
        if (session == null || !(session.view().getTopInventory() instanceof CraftingInventory crafting)) {
            refuse(player, request, "Open a Linked Workbench first.");
            return;
        }

        List<StorageScanner.Source> sources = sources(workbench, player, session);
        List<ItemStack> keys = new ArrayList<>();
        List<GridPlanner.Supply> supply = new ArrayList<>();
        index(player, workbench, sources, keys, supply);

        List<GridPlanner.Slot> slots = slotsOf(request, keys);
        if (slots == null) {
            refuse(player, request, "That recipe is not one this server knows.");
            return;
        }
        int[] maxStack = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            maxStack[i] = Math.max(1, Math.min(keys.get(i).getMaxStackSize(), 64));
        }

        GridPlanner.Plan plan = GridPlanner.plan(slots, supply, maxStack, request.maxTransfer());
        if (!plan.ok()) {
            refuse(player, request, "Not enough in range: " + plan.failure() + ".");
            return;
        }

        // Clear first, by the same rules a close uses, so nothing already in the grid is lost
        // and anything that came from a chest goes back to that chest.
        workbench.sessions().drainGrid(player, session, true);
        ItemStack[] matrix = crafting.getMatrix();
        for (GridPlanner.Fill fill : plan.fills()) {
            ItemStack key = keys.get(fill.type());
            int taken = 0;
            if (fill.fromPlayer() > 0) {
                taken += takeFromPlayer(player, key, fill.fromPlayer());
            }
            if (fill.fromStorage() > 0) {
                for (StorageScanner.Pulled pulled : workbench.scanner().pull(sources, key, fill.fromStorage())) {
                    taken += pulled.stack().getAmount();
                    session.addOrigin(fill.gridIndex(), pulled.source().location(), pulled.stack().getAmount());
                }
            }
            if (taken > 0 && fill.gridIndex() >= 0 && fill.gridIndex() < matrix.length) {
                matrix[fill.gridIndex()] = key.clone().asQuantity(taken);
            }
        }
        crafting.setMatrix(matrix);
        player.updateInventory();

        send(player, LinkProtocol.CHANNEL_TRANSFER_RESULT,
                LinkProtocol.encode(new LinkProtocol.TransferResult(request.requestId(), true, "")));
        push(player, true); // the chests just changed, and by more than a delta is worth
    }

    /**
     * The player clicked an item in the storage panel. The same rules as clicking a phantom
     * slot, because it goes through the same {@link StoragePull}: a stack or half a stack to
     * the cursor, as many as fit into the inventory, and anything that fits nowhere back into
     * storage rather than onto the floor.
     */
    private void onPullRequest(Player player, LinkProtocol.PullRequest request) {
        Linked link = linked.get(player.getUniqueId());
        if (link == null) {
            return;
        }
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        LinkedSession session = workbench == null ? null : workbench.sessions().of(player);
        if (session == null) {
            reply(player, request.requestId(), false, "Open a Linked Workbench first.");
            return;
        }
        ItemStack wanted = blobs.decode(request.item());
        if (Items.isEmpty(wanted)) {
            reply(player, request.requestId(), false, "That is not an item this server can read.");
            return;
        }
        PullPlanner.Mode mode;
        try {
            mode = PullPlanner.Mode.valueOf(request.mode());
        } catch (IllegalArgumentException unknown) {
            reply(player, request.requestId(), false, "Unknown click.");
            return;
        }
        if (mode != PullPlanner.Mode.ALL && !Items.isEmpty(player.getItemOnCursor())) {
            // Same guard the phantom path has: with something already on the cursor this
            // click was a place, not a take.
            reply(player, request.requestId(), false, "");
            return;
        }

        List<StorageScanner.Source> sources = sources(workbench, player, session);
        ItemStack key = StorageScanner.keyOf(wanted);
        StoragePull.Result result = StoragePull.pull(player, workbench.scanner(), sources, key, mode,
                StoragePull.freeInventorySlots(session.view()), over -> putBack(player, sources, over));
        if (!result.happened()) {
            reply(player, request.requestId(), false, "No room, or none left in range.");
            push(player, true);
            return;
        }
        player.updateInventory();
        reply(player, request.requestId(), true, "");
        push(player, true); // the chests just changed
    }

    /** Anything that fits neither cursor nor inventory goes back where it came from. */
    private void putBack(Player player, List<StorageScanner.Source> sources, ItemStack stack) {
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        ItemStack left = workbench == null ? stack : workbench.scanner().deposit(sources, stack);
        if (Items.isEmpty(left)) {
            return;
        }
        plugin.getLogger().warning("CraftBridge client link: " + Items.describe(left) + " x" + left.getAmount()
                + " fit neither " + player.getName() + "'s inventory nor nearby storage; dropping it at their feet.");
        player.getWorld().dropItemNaturally(player.getLocation(), left);
    }

    private void reply(Player player, int requestId, boolean ok, String message) {
        send(player, LinkProtocol.CHANNEL_TRANSFER_RESULT,
                LinkProtocol.encode(new LinkProtocol.TransferResult(requestId, ok, message)));
    }

    private void refuse(Player player, LinkProtocol.TransferRequest request, String why) {
        send(player, LinkProtocol.CHANNEL_TRANSFER_RESULT,
                LinkProtocol.encode(new LinkProtocol.TransferResult(request.requestId(), false, why)));
        push(player, true); // whatever the client believed, this is what is actually there
    }

    /** Build the item-type table: every distinct type the player carries or has in range. */
    private void index(Player player, WorkbenchFeature workbench, List<StorageScanner.Source> sources,
                       List<ItemStack> keys, List<GridPlanner.Supply> supply) {
        Map<ItemStack, int[]> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (!Items.isEmpty(stack)) {
                counts.computeIfAbsent(StorageScanner.keyOf(stack), k -> new int[2])[0] += stack.getAmount();
            }
        }
        workbench.scanner().aggregate(sources).forEach((key, count) ->
                counts.computeIfAbsent(key, k -> new int[2])[1] += count);
        counts.forEach((key, both) -> {
            keys.add(key);
            supply.add(new GridPlanner.Supply(both[0], both[1]));
        });
    }

    /**
     * What each grid slot needs, as indices into {@code keys}. A registered recipe is looked up
     * by name and read from the server's own copy; a display with no recipe of its own is
     * described by the choices the client sent, which are only ever used to <em>match</em>
     * items the server has already counted.
     */
    private List<GridPlanner.Slot> slotsOf(LinkProtocol.TransferRequest request, List<ItemStack> keys) {
        if (request.recipeId() != null && !request.recipeId().isEmpty()) {
            NamespacedKey key = NamespacedKey.fromString(request.recipeId());
            Recipe recipe = key == null ? null : Bukkit.getRecipe(key);
            if (!(recipe instanceof CraftingRecipe crafting)) {
                return null;
            }
            return fromRecipe(crafting, keys);
        }
        if (request.slots() == null || request.slots().isEmpty()) {
            return null;
        }
        List<GridPlanner.Slot> slots = new ArrayList<>();
        for (LinkProtocol.SlotChoices choices : request.slots()) {
            List<ItemStack> wanted = new ArrayList<>();
            for (byte[] blob : choices.choices()) {
                ItemStack decoded = blobs.decode(blob);
                if (decoded != null) {
                    wanted.add(StorageScanner.keyOf(decoded));
                }
            }
            slots.add(new GridPlanner.Slot(choices.gridIndex(), matching(keys, k -> wanted.stream().anyMatch(k::isSimilar))));
        }
        return slots;
    }

    private static List<GridPlanner.Slot> fromRecipe(CraftingRecipe recipe, List<ItemStack> keys) {
        List<GridPlanner.Slot> slots = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (int row = 0; row < shape.length; row++) {
                String line = shape[row];
                for (int col = 0; col < line.length(); col++) {
                    RecipeChoice choice = choices.get(line.charAt(col));
                    if (choice != null) {
                        slots.add(new GridPlanner.Slot(row * 3 + col, matching(keys, choice::test)));
                    }
                }
            }
            return slots;
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<RecipeChoice> choices = shapeless.getChoiceList();
            for (int i = 0; i < choices.size() && i < 9; i++) {
                RecipeChoice choice = choices.get(i);
                if (choice != null) {
                    slots.add(new GridPlanner.Slot(i, matching(keys, choice::test)));
                }
            }
            return slots;
        }
        return null;
    }

    /** The indices of every item type the test accepts, in table order. */
    private static int[] matching(List<ItemStack> keys, java.util.function.Predicate<ItemStack> test) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (test.test(keys.get(i))) {
                hits.add(i);
            }
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hits.get(i);
        }
        return out;
    }

    /** Take up to {@code wanted} of this item out of the player's inventory; returns how many. */
    private static int takeFromPlayer(Player player, ItemStack key, int wanted) {
        ItemStack ask = key.clone().asQuantity(wanted);
        int notTaken = player.getInventory().removeItem(ask).values().stream()
                .mapToInt(ItemStack::getAmount).sum();
        return wanted - notTaken;
    }

    private void send(Player player, String channel, byte[] payload) {
        if (player.isOnline()) {
            player.sendPluginMessage(plugin, channel, payload);
        }
    }
}
