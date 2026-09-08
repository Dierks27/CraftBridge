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
