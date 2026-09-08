package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /sort settings}: pick a trigger, toggle "also sort my inventory" and feedback.
 * A 27-slot chest layout so it renders the same through Geyser.
 */
public final class SortSettingsMenu extends Menu {

    private static final int[] TRIGGER_SLOTS = {10, 11, 12, 13};
    private static final Material[] TRIGGER_ICONS = {Material.HOPPER, Material.REPEATER, Material.STONE_AXE, Material.PAPER};

    private final SortFeature feature;
    private final Player player;

    public SortSettingsMenu(SortFeature feature, Player player) {
        this.feature = feature;
        this.player = player;
        init(27, Text.item("<dark_aqua>Sorting settings"));
    }

    @Override
    protected void build() {
        PlayerSortSettings settings = feature.settings(player);
        SortTrigger[] triggers = SortTrigger.values();
        for (int i = 0; i < triggers.length && i < TRIGGER_SLOTS.length; i++) {
            SortTrigger trigger = triggers[i];
            boolean selected = settings.trigger() == trigger;
            List<String> lore = new ArrayList<>();
            lore.add(trigger.hint());
            if (trigger == SortTrigger.SHIFT_CLICK_OUTSIDE) {
                lore.add("<dark_gray>If this does nothing on your client,");
                lore.add("<dark_gray>use double-click or sneak+punch.");
            }
            lore.add("");
            lore.add(selected ? "<green>Selected" : "<yellow>Click to select");
            ItemStack icon = Items.icon(TRIGGER_ICONS[i], (selected ? "<green>" : "<white>") + trigger.title(), Text.lore(lore));
            Items.glint(icon, selected);
            set(TRIGGER_SLOTS[i], icon, e -> {
                feature.update(player, s -> s.withTrigger(trigger));
                refresh();
            });
        }

        if (feature.playerInventoryAllowed()) {
            set(15, Icons.toggle(settings.sortPlayerInventory(), "<white>Also sort my inventory",
                    "Sorting a chest also tidies your own",
                    "main inventory rows (never the hotbar)."), e -> {
                feature.update(player, s -> s.withSortPlayerInventory(!s.sortPlayerInventory()));
                refresh();
            });
        }
        set(16, Icons.toggle(settings.feedback(), "<white>Sound & particles",
                "A little chime and sparkle when a sort runs."), e -> {
            feature.update(player, s -> s.withFeedback(!s.feedback()));
            refresh();
        });

        set(22, Items.icon(Material.BOOK, "<aqua>About triggers",
                "Middle-click can't be a trigger: in survival",
                "the client never sends a middle-click packet,",
                "so the server can't see it. If middle-click",
                "sorts for you today, that's a client-side mod.",
                "",
                "/sort always works while a chest is open."), null);
        set(26, Icons.close(), e -> player.closeInventory());
        fill(Icons.filler());
    }
}
