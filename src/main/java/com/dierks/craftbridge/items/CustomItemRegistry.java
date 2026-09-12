package com.dierks.craftbridge.items;

import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The custom-item registry: definitions in, stamped {@link ItemStack}s out, and the id back
 * off any stack.
 *
 * <h2>Why the id lives in a PersistentDataContainer</h2>
 * A display name is not an identity. Any player with an anvil can rename a plain dried kelp
 * to "Burned Zombie Flesh" and, if recipes matched on the name, forge one. A PDC value
 * cannot be produced without {@code /give} with raw NBT, which is an operator-level
 * capability, so it is the only field here safe to match on.
 *
 * <p>Stamped items deliberately do not stack with their plain vanilla counterparts — that
 * is what keeps the two tellable apart in a chest, and it is intended.
 */
public final class CustomItemRegistry {

    /** {@code craftbridge:cb_item} — the string id of the definition an item was built from. */
    public static final NamespacedKey ITEM_ID = Keys.key("cb_item");

    private final Map<String, CustomItemDef> defs = new LinkedHashMap<>();

    public void replaceAll(Map<String, CustomItemDef> replacement) {
        defs.clear();
        defs.putAll(replacement);
    }

    public void put(CustomItemDef def) {
        defs.put(def.id(), def);
    }

    public CustomItemDef remove(String id) {
        return defs.remove(id);
    }

    public CustomItemDef get(String id) {
        return id == null ? null : defs.get(id);
    }

    public boolean contains(String id) {
        return id != null && defs.containsKey(id);
    }

    public Collection<CustomItemDef> all() {
        return defs.values();
    }

    public boolean isEmpty() {
        return defs.isEmpty();
    }

    /** {@code burned_zombie_flesh}, then {@code burned_zombie_flesh_2}, ... */
    public String freeId(String base) {
        String root = CustomItemIds.sanitise(base);
        if (root.isEmpty()) {
            root = "item";
        }
        if (!defs.containsKey(root)) {
            return root;
        }
        for (int i = 2; ; i++) {
            String candidate = root + "_" + i;
            if (!defs.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    // ---- building -----------------------------------------------------------

    /** A fresh stack of the definition, or null when no such definition exists. */
    public ItemStack create(String id, int amount) {
        CustomItemDef def = get(id);
        return def == null ? null : create(def, amount);
    }

    public ItemStack create(String id) {
        return create(id, 1);
    }

    /**
     * Build the item. The head texture is committed on its own pass before the name, lore and
     * PDC are written: setting a {@link SkullMeta} profile and then reusing the same meta
     * instance drops the other fields on Paper 26.2, so the meta is re-fetched in between.
     * This mirrors the two-pass rule HomeCraftManagement's {@code Heads} helper documents.
     */
    public ItemStack create(CustomItemDef def, int amount) {
        ItemStack item = new ItemStack(def.base(), Math.max(1, amount));
        if (def.isTexturedHead()) {
            applyTexture(item, def.headTexture());
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.item(def.name()));
        if (!def.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(def.lore().size());
            for (String line : def.lore()) {
                lore.add(Text.item(line));
            }
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(ITEM_ID, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    private static void applyTexture(ItemStack head, String texture) {
        if (!(head.getItemMeta() instanceof SkullMeta skull)) {
            return;
        }
        try {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
            profile.setProperty(new ProfileProperty("textures", texture));
            skull.setPlayerProfile(profile);
            head.setItemMeta(skull);
        } catch (RuntimeException ex) {
            // A malformed texture value must not stop the item existing — it just looks plain.
            head.setItemMeta(skull);
        }
    }

    // ---- reading ------------------------------------------------------------

    /** The custom-item id stamped on this stack, or null when it carries none. */
    public static String idOf(ItemStack stack) {
        if (Items.isEmpty(stack) || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(ITEM_ID, PersistentDataType.STRING);
    }

    /** True when this stack carries any CraftBridge custom-item stamp. */
    public static boolean isStamped(ItemStack stack) {
        return idOf(stack) != null;
    }

    /** True when this stack is the given custom item. */
    public static boolean is(ItemStack stack, String id) {
        return id != null && id.equals(idOf(stack));
    }

    /** Every defined item as a fresh stack, for the item picker. */
    public List<ItemStack> allStacks() {
        List<ItemStack> out = new ArrayList<>(defs.size());
        for (CustomItemDef def : defs.values()) {
            out.add(create(def, 1));
        }
        return out;
    }

    /** True when a definition's base is a player head, i.e. it must be kept out of the world. */
    public boolean isHeadItem(ItemStack stack) {
        String id = idOf(stack);
        if (id == null) {
            return false;
        }
        CustomItemDef def = get(id);
        return def != null && def.base() == Material.PLAYER_HEAD;
    }
}
