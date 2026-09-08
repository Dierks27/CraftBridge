package com.dierks.craftbridge.gui;

import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** A yes/no question in a 27-slot chest (Geyser-friendly). */
public final class ConfirmMenu extends Menu {

    private final Player player;
    private final String question;
    private final String[] detail;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private boolean answered;

    public ConfirmMenu(Player player, String title, String question, String[] detail, Runnable onConfirm, Runnable onCancel) {
        this.player = player;
        this.question = question;
        this.detail = detail;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        init(27, Text.item(title));
    }

    @Override
    protected void build() {
        set(13, Items.icon(Material.PAPER, question, detail), null);
        set(11, Items.icon(Material.LIME_CONCRETE, "<green>Yes, do it"), e -> {
            answered = true;
            onConfirm.run();
        });
        set(15, Items.icon(Material.RED_CONCRETE, "<red>No, go back"), e -> {
            answered = true;
            onCancel.run();
        });
        fill(Icons.filler());
    }

    @Override
    protected void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!answered) {
            answered = true;
            // Closing with Escape counts as "no", but do it next tick: we are inside the close event.
            org.bukkit.Bukkit.getScheduler().runTask(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("CraftBridge"), onCancel);
        }
    }

    public Player player() {
        return player;
    }
}
