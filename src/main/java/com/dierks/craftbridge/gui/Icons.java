package com.dierks.craftbridge.gui;

import com.dierks.craftbridge.util.Items;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Shared GUI icons. */
public final class Icons {

    private Icons() {
    }

    public static ItemStack filler() {
        return Items.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    public static ItemStack close() {
        return Items.icon(Material.BARRIER, "<red>Close");
    }

    public static ItemStack back() {
        return Items.icon(Material.ARROW, "<yellow>Back");
    }

    public static ItemStack toggle(boolean on, String name, String... lore) {
        String[] full = new String[lore.length + 1];
        System.arraycopy(lore, 0, full, 0, lore.length);
        full[lore.length] = on ? "<green>Enabled <dark_gray>(click to disable)" : "<red>Disabled <dark_gray>(click to enable)";
        return Items.icon(on ? Material.LIME_DYE : Material.GRAY_DYE, name, full);
    }
}
