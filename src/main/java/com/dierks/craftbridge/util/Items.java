package com.dierks.craftbridge.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Small ItemStack helpers shared by all features. */
public final class Items {

    private Items() {
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** A GUI icon: named, with lore, tagged as a button so it can never be mistaken for a real item. */
    public static ItemStack icon(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            meta.getPersistentDataContainer().set(Keys.GUI_BUTTON, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, Text.lore(lore));
    }

    /** Add or remove the enchantment glint used to mark a selected option. */
    public static ItemStack glint(ItemStack item, boolean on) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(on ? Boolean.TRUE : null);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isButton(ItemStack stack) {
        if (isEmpty(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(Keys.GUI_BUTTON, PersistentDataType.BYTE);
    }

    /** Plain-text name of an item for lore/logs: custom name if present, else the material. */
    public static String describe(ItemStack stack) {
        if (isEmpty(stack)) {
            return "nothing";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return prettyMaterial(stack.getType());
    }

    public static String prettyMaterial(Material material) {
        String[] parts = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
