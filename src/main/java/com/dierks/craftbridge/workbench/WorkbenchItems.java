package com.dierks.craftbridge.workbench;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Place-items and display items for every {@link BlockKind}.
 *
 * <p>A kind with a configured head texture is a player head wearing it (built in two
 * passes: profile first, then name/lore/PDC on a fresh meta — on the live 26.2 API
 * setting the profile on a meta that already carries a name and PDC can drop everything
 * but the name). A kind without a texture uses its {@code display-item} material (the
 * Combo Chest defaults to a plain chest until Jeff supplies a texture). Every place-item
 * is PDC-tagged with the kind's tag.
 */
public final class WorkbenchItems {

    private final CraftBridgePlugin plugin;

    public WorkbenchItems(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** The undecorated item the ItemDisplay shows: textured head, or the configured display material. */
    public ItemStack displayItem(BlockKind kind) {
        String texture = plugin.config().headTexture(kind);
        if (texture != null && !texture.isBlank()) {
            return texturedHead(kind, texture);
        }
        return new ItemStack(plugin.config().displayMaterial(kind));
    }

    private static ItemStack texturedHead(BlockKind kind, String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                UUID id = UUID.nameUUIDFromBytes(("craftbridge:" + kind.id()).getBytes(StandardCharsets.UTF_8));
                PlayerProfile profile = Bukkit.createProfile(id, "CraftBridge" + kind.ordinal());
                profile.setProperty(new ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
                head.setItemMeta(skull);
            } catch (RuntimeException ignored) {
                // Malformed texture value: fall back to a plain head rather than fail.
            }
        }
        return head;
    }

    /** The place-item (named, lore, PDC-tagged). */
    public ItemStack placeItem(BlockKind kind, int amount) {
        ItemStack item = displayItem(kind);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item("<aqua>" + kind.displayName()));
            meta.lore(Text.lore(kind == BlockKind.WORKBENCH
                    ? List.of("A crafting table that crafts straight",
                              "from chests, barrels and shulkers nearby.",
                              "",
                              "<yellow>Right-click <gray>craft; JEI [+] pulls from storage")
                    : List.of("A storage terminal: everything in the",
                              "chests, barrels and shulkers nearby.",
                              "",
                              "<yellow>Right-click <gray>browse, take and deposit")));
            meta.getPersistentDataContainer().set(kind.itemTag(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    /** Which kind of place-item this is, or null if it is an ordinary item. */
    public static BlockKind kindOf(ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        for (BlockKind kind : BlockKind.values()) {
            if (meta.getPersistentDataContainer().has(kind.itemTag(), PersistentDataType.BYTE)) {
                return kind;
            }
        }
        return null;
    }
}
