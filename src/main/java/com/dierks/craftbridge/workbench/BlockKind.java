package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.util.Keys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import java.util.Locale;

/** The custom blocks that share the "real block underneath + tracked record + display" pattern. */
public enum BlockKind {
    /** A crafting table that crafts from nearby storage. */
    WORKBENCH("workbench", "linked_workbench", Material.CRAFTING_TABLE, "Linked Workbench", "linked-workbench"),
    /** A storage terminal: browse, pull from and deposit into every container in range. */
    COMBO_CHEST("combo_chest", "combo_chest", Material.BARREL, "Combo Chest", "combo-chest");

    private final String id;
    private final NamespacedKey itemTag;
    private final Material block;
    private final String displayName;
    private final String configSection;

    BlockKind(String id, String tag, Material block, String displayName, String configSection) {
        this.id = id;
        this.itemTag = Keys.key(tag);
        this.block = block;
        this.displayName = displayName;
        this.configSection = configSection;
    }

    /** Stored in linked-workbenches.yml as {@code type}. */
    public String id() {
        return id;
    }

    /** PDC tag on the place-item ({@code craftbridge:linked_workbench} / {@code craftbridge:combo_chest}). */
    public NamespacedKey itemTag() {
        return itemTag;
    }

    /** The real block placed in the world (hitbox, protection checks, vanilla behaviour). */
    public Material block() {
        return block;
    }

    public String displayName() {
        return displayName;
    }

    /** Top-level config.yml section for this kind. */
    public String configSection() {
        return configSection;
    }

    /**
     * The head texture used when {@code head-texture} is blank. Empty for kinds that have
     * no default look of their own, which then fall back to {@code display-item}.
     */
    public String defaultHeadTexture() {
        return this == COMBO_CHEST ? DEFAULT_COMBO_CHEST_TEXTURE : "";
    }

    /** The recipe shipped for this block, used whenever config.yml does not describe a usable one. */
    public java.util.List<String> defaultRecipeShape() {
        return this == COMBO_CHEST ? java.util.List.of("HEH", "CBC", "HRH") : java.util.List.of("HCH", "CTC", "HEH");
    }

    /** Ingredient letters for {@link #defaultRecipeShape()}. */
    public java.util.Map<Character, Material> defaultRecipeIngredients() {
        java.util.Map<Character, Material> out = new java.util.LinkedHashMap<>();
        out.put('H', Material.CHEST);
        out.put('C', Material.COPPER_INGOT);
        out.put('E', Material.ENDER_PEARL);
        if (this == COMBO_CHEST) {
            out.put('B', Material.BARREL);
            out.put('R', Material.COMPARATOR);
        } else {
            out.put('T', Material.CRAFTING_TABLE);
        }
        return out;
    }

    /** Base64 "textures" value for the Combo Chest's head (chosen for the live server). */
    private static final String DEFAULT_COMBO_CHEST_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1"
            + "cmUvNmJiMGUzYzE4YzczZTNhMmNhYmNjZjQ0ZWY1M2Q2MTdjYTA5NWUwZjIzNDdlZmE5M2Y2N2JkNDgwOGU3MGE3In19fQ==";

    public NamespacedKey recipeKey() {
        return new NamespacedKey("craftbridge", id);
    }

    public static BlockKind byId(String id) {
        if (id == null) {
            return WORKBENCH;
        }
        String norm = id.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (BlockKind k : values()) {
            if (k.id.equals(norm)) {
                return k;
            }
        }
        return switch (norm) {
            case "combochest", "combo", "chest", "terminal" -> COMBO_CHEST;
            case "linkedworkbench", "table", "linked_table" -> WORKBENCH;
            default -> null;
        };
    }
}
