package com.dierks.craftbridge.items;

import org.bukkit.Material;

import java.util.List;

/**
 * One custom item definition: a vanilla item (or a textured player head) with a display
 * name, lore and a stable id. No resource pack is involved, so Bedrock players see and use
 * these exactly like Java players do.
 *
 * <p>The id — not the display name — is the identity. It is stamped into the item's
 * {@link CustomItemRegistry#ITEM_ID} PDC key; see that class for why the name is not usable
 * as an identity.
 *
 * @param id          stable id, e.g. {@code burned_zombie_flesh} (see {@link CustomItemIds})
 * @param base        the vanilla material the item is built on
 * @param name        display name as MiniMessage, e.g. {@code <dark_gray>Burned Zombie Flesh}
 * @param lore        lore lines as MiniMessage, may be empty
 * @param headTexture base64 texture value when {@code base} is a player head, else null
 */
public record CustomItemDef(String id, Material base, String name, List<String> lore, String headTexture) {

    public CustomItemDef {
        String problem = CustomItemIds.problem(id);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (base == null || !base.isItem()) {
            throw new IllegalArgumentException("base material must be an item");
        }
        name = name == null || name.isBlank() ? id : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
        headTexture = headTexture == null || headTexture.isBlank() ? null : headTexture;
    }

    public static CustomItemDef of(String id, Material base, String name) {
        return new CustomItemDef(id, base, name, List.of(), null);
    }

    /** True when this definition needs Paper's profile API to build its head texture. */
    public boolean isTexturedHead() {
        return headTexture != null && base == Material.PLAYER_HEAD;
    }

    public CustomItemDef withBase(Material material) {
        return new CustomItemDef(id, material, name, lore, headTexture);
    }

    public CustomItemDef withName(String value) {
        return new CustomItemDef(id, base, value, lore, headTexture);
    }

    public CustomItemDef withLore(List<String> value) {
        return new CustomItemDef(id, base, name, value, headTexture);
    }

    public CustomItemDef withHeadTexture(String value) {
        return new CustomItemDef(id, base, name, lore, value);
    }
}
