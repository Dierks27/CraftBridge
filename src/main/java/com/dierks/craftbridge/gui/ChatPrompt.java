package com.dierks.craftbridge.gui;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type the next thing you say into chat" — the one piece of text input CraftBridge needs
 * that a chest GUI cannot provide (an item's display name and lore).
 *
 * <p>Chat rather than an anvil GUI on purpose: the rest of the plugin is deliberately
 * chest-GUI-only so Geyser/Bedrock players get identical screens, and an anvil rename field
 * is one of the places Bedrock behaves differently. Every Bedrock client can type in chat.
 *
 * <p>The prompt closes the player's menu, waits for one line, and reopens whatever the
 * caller reopens. The chat message itself is always cancelled so a half-typed item name
 * never lands in public chat, and the wait is dropped on quit so nothing leaks.
 */
public final class ChatPrompt implements Listener {

    /** What a player types to abandon the prompt. */
    public static final String CANCEL_WORD = "cancel";

    private record Pending(Consumer<String> onText, Runnable onCancel) {
    }

    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    public ChatPrompt(CraftBridgePlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Ask the player for one line of text.
     *
     * @param question what to ask, as MiniMessage (already prefixed)
     * @param onText   run on the main thread with the typed line
     * @param onCancel run on the main thread when they type {@code cancel}
     */
    public void ask(CraftBridgePlugin plugin, Player player, String question,
                    Consumer<String> onText, Runnable onCancel) {
        // Register the wait first, then close on the next tick: ask() is called from an
        // InventoryClickEvent handler, and closing the inventory while the click is still
        // being processed is not safe.
        waiting.put(player.getUniqueId(), new Pending(
                text -> plugin.getServer().getScheduler().runTask(plugin, () -> onText.accept(text)),
                () -> plugin.getServer().getScheduler().runTask(plugin, onCancel)));
        // An explicit lambda, not player::closeInventory — a method reference is not pertinent
        // to applicability, so it is ambiguous between runTask(Plugin, Runnable) and
        // runTask(Plugin, Consumer<? super BukkitTask>). A zero-arg lambda can only be Runnable.
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        player.sendMessage(Text.msg(question));
        player.sendMessage(Text.msg("<dark_gray>Type <white>" + CANCEL_WORD + "<dark_gray> to go back."));
    }

    public void forget(Player player) {
        waiting.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Pending pending = waiting.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        // Always cancel: a partly-typed item name must never reach public chat.
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.isEmpty() || text.equalsIgnoreCase(CANCEL_WORD)) {
            pending.onCancel().run();
            return;
        }
        pending.onText().accept(text);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        waiting.clear();
        HandlerList.unregisterAll(this);
    }
}
