package com.dierks.craftbridge.jei;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 1: be the "server side of JEI" for recipe transfer.
 *
 * <ol>
 *   <li>Register the JEI transfer channels (incoming) and the result channel (outgoing)
 *       with Bukkit's Messenger. Paper announces incoming channels in {@code minecraft:register},
 *       which is exactly what flips {@code isJeiOnServer} on a Fabric/JEI client and enables
 *       the {@code [+]} button.</li>
 *   <li>Decode the payload (VarInt slot ids only — JEI 26.2 sends no ItemStacks) and run the
 *       ported transfer algorithm against the player's open crafting table or 2x2 grid.</li>
 *   <li>Reply on {@code jei:recipe_transfer_result} so JEI's client completes the pending transfer.</li>
 * </ol>
 * Everything runs on the main thread; decode failures are logged at DEBUG with the byte
 * count so a bad client cannot spam the console.
 */
public final class JeiTransferFeature implements CraftBridgePlugin.Feature, PluginMessageListener {

    private final CraftBridgePlugin plugin;
    private final TransferEngine<ItemStack> engine = new TransferEngine<>(new TransferEngine.Model<>() {
        @Override
        public int maxStack(ItemStack key) {
            return Math.max(1, Math.min(key.getMaxStackSize(), 64));
        }

        @Override
        public boolean same(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }
    });
    private final List<TransferListener> listeners = new ArrayList<>();
    private final Set<String> announced = ConcurrentHashMap.newKeySet();

    public JeiTransferFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "jei-transfer";
    }

    @Override
    public void enable() {
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : JeiChannels.INCOMING) {
            messenger.registerIncomingPluginChannel(plugin, channel, this);
        }
        for (String channel : JeiChannels.OUTGOING) {
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
        plugin.getLogger().info("JEI recipe transfer: listening on " + String.join(", ", JeiChannels.INCOMING)
                + " (built against " + JeiChannels.BUILT_AGAINST + "; these packets carry no protocol version).");
    }

    @Override
    public void disable() {
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin);
        messenger.unregisterOutgoingPluginChannel(plugin);
        listeners.clear();
    }

    /** Hook for other features (the Linked Workbench) to react after a transfer. */
    public void addListener(TransferListener listener) {
        listeners.add(listener);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!JeiChannels.isTransfer(channel)) {
            return; // jei:delete_player_item is only registered as the presence marker
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handle(channel, player, message));
            return;
        }
        handle(channel, player, message);
    }

    private void handle(String channel, Player player, byte[] message) {
        if (announced.add(channel)) {
            plugin.getLogger().info("First JEI packet on " + channel + " from " + player.getName()
                    + " (" + message.length + " bytes) — recipe transfer is live for JEI clients.");
        }
        TransferPacket packet;
        try {
            packet = TransferPacket.decode(message, JeiChannels.isCounted(channel), JeiChannels.hasResult(channel));
        } catch (RuntimeException ex) {
            plugin.debug("JEI transfer packet from " + player.getName() + " on " + channel + " could not be decoded ("
                    + message.length + " bytes): " + ex.getMessage());
            return;
        }
        boolean success = transfer(player, packet);
        if (packet.expectsResult()) {
            player.sendPluginMessage(plugin, JeiChannels.RECIPE_TRANSFER_RESULT,
                    TransferPacket.encodeResult(packet.transferId(), success));
        }
    }

    /** Run the transfer against the player's open crafting view. */
    public boolean transfer(Player player, TransferPacket packet) {
        InventoryView view = player.getOpenInventory();
        GridLayout layout = GridLayout.of(view.getType().name());
        if (layout == null) {
            plugin.debug("JEI transfer from " + player.getName() + " rejected: open view is " + view.getType()
                    + ", not a crafting table or the player grid.");
            return false;
        }
        Map<Integer, TransferEngine.Stack<ItemStack>> before = new HashMap<>();
        for (int raw : layout.allSlots()) {
            ItemStack stack = view.getItem(raw);
            if (!Items.isEmpty(stack)) {
                ItemStack key = stack.clone();
                key.setAmount(1);
                before.put(raw, new TransferEngine.Stack<>(key, stack.getAmount()));
            }
        }
        TransferEngine.Result<ItemStack> result = engine.apply(before, packet, layout.gridSlots(), layout.inventorySlots());
        if (!result.success()) {
            plugin.debug("JEI transfer from " + player.getName() + " failed: " + result.failure());
        } else {
            for (int raw : layout.allSlots()) {
                TransferEngine.Stack<ItemStack> wanted = result.slots().get(raw);
                TransferEngine.Stack<ItemStack> had = before.get(raw);
                if (sameStack(wanted, had)) {
                    continue;
                }
                view.setItem(raw, wanted == null ? null : wanted.key().clone().asQuantity(wanted.count()));
            }
            for (TransferEngine.Stack<ItemStack> extra : result.overflow()) {
                ItemStack stack = extra.key().clone().asQuantity(extra.count());
                for (ItemStack left : player.getInventory().addItem(stack).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
            }
            player.updateInventory();
        }
        for (TransferListener listener : listeners) {
            try {
                listener.afterTransfer(player, view, layout, packet, result.success());
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("JEI transfer listener failed: " + ex);
            }
        }
        return result.success();
    }

    private static boolean sameStack(TransferEngine.Stack<ItemStack> a, TransferEngine.Stack<ItemStack> b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.count() == b.count() && a.key().isSimilar(b.key());
    }
}
