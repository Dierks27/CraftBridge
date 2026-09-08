package com.dierks.craftbridge.workbench;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Linked Workbench place-item: a player head wearing the configured texture, named,
 * and tagged {@code craftbridge:linked_workbench} in its PDC.
 *
 * <p>Built in two passes (profile first, then name/lore/PDC on a fresh meta) — on the
 * live 26.2 API setting the profile on a meta that already carries a name and PDC can
 * drop everything but the name.
 */
public final class WorkbenchItems {

    public static final NamespacedKey LINKED_WORKBENCH = Keys.key("linked_workbench");
    /** Fixed profile identity so every head is the same "player" (no lookups, no cache churn). */
    private static final UUID PROFILE_ID = UUID.nameUUIDFromBytes("craftbridge:linked_workbench".getBytes(StandardCharsets.UTF_8));

    private final String texture;

    public WorkbenchItems(String texture) {
        this.texture = texture;
    }

    /** A bare textured head — used for the block display. */
    public ItemStack texturedHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (texture == null || texture.isBlank()) {
            return head;
        }
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                PlayerProfile profile = Bukkit.createProfile(PROFILE_ID, "LinkedWorkbench");
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
    public ItemStack placeItem(int amount) {
        ItemStack item = texturedHead();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item("<aqua>Linked Workbench"));
            meta.lore(Text.lore("A crafting table that can pull from",
                    "chests, barrels and shulkers nearby.",
                    "",
                    "<yellow>Right-click <gray>craft (JEI [+] works)",
                    "<yellow>Sneak + right-click <gray>browse nearby storage"));
            meta.getPersistentDataContainer().set(LINKED_WORKBENCH, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, amount));
        return item;
    }

    public static boolean isPlaceItem(ItemStack stack) {
        if (Items.isEmpty(stack) || stack.getType() != Material.PLAYER_HEAD) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(LINKED_WORKBENCH, PersistentDataType.BYTE);
    }
}
