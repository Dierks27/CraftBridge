package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.items.CustomItemChoice;
import com.dierks.craftbridge.items.CustomItemRegistry;
import com.dierks.craftbridge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.util.Locale;

/**
 * One crafting or cooking ingredient. Four flavours:
 * <ul>
 *   <li><b>material</b> — any item of that material (what the editor produces by default);</li>
 *   <li><b>exact</b> — the exact item (name, enchantments, components) placed in the editor;</li>
 *   <li><b>custom</b> — a CraftBridge custom item, matched by its {@code cb_item} id;</li>
 *   <li><b>tag</b> — an item tag such as {@code minecraft:wool} (hand-written recipes only).</li>
 * </ul>
 *
 * <p>A <b>custom</b> ingredient is stored as the id, never as a serialised stack, so editing
 * the definition (a new lore line, a corrected colour) updates every recipe that uses it
 * instead of leaving them matching a stale copy. It resolves to a freshly built item at
 * registration time.
 */
public final class Ingredient {

    private final Material material;
    private final ItemStack exact;
    private final NamespacedKey tag;
    private final String customId;

    private Ingredient(Material material, ItemStack exact, NamespacedKey tag, String customId) {
        this.material = material;
        this.exact = exact;
        this.tag = tag;
        this.customId = customId;
    }

    public static Ingredient ofMaterial(Material material) {
        return new Ingredient(material, null, null, null);
    }

    public static Ingredient ofExact(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return new Ingredient(one.getType(), one, null, null);
    }

    public static Ingredient ofTag(NamespacedKey tag) {
        return new Ingredient(null, null, tag, null);
    }

    /** A CraftBridge custom item, referenced by id and resolved when the recipe is registered. */
    public static Ingredient ofCustom(String customId) {
        return new Ingredient(null, null, null, customId);
    }

    public boolean isExact() {
        return exact != null;
    }

    public boolean isTag() {
        return tag != null;
    }

    public boolean isCustom() {
        return customId != null;
    }

    public String customId() {
        return customId;
    }

    public Material material() {
        return material;
    }

    public ItemStack exact() {
        return exact;
    }

    public NamespacedKey tag() {
        return tag;
    }

    /** Same ingredient for the purpose of sharing a shape letter. */
    public boolean sameAs(Ingredient other) {
        if (other == null) {
            return false;
        }
        if (isCustom() || other.isCustom()) {
            return isCustom() && other.isCustom() && customId.equals(other.customId);
        }
        if (isTag() || other.isTag()) {
            return isTag() && other.isTag() && tag.equals(other.tag);
        }
        if (isExact() != other.isExact()) {
            return false;
        }
        return isExact() ? exact.isSimilar(other.exact) : material == other.material;
    }

    /**
     * The item shown in GUIs / used for conflict matching.
     *
     * <p>A custom ingredient needs the registry to resolve its id; {@link #display()} without
     * one falls back to a barrier, which is what an orphaned reference should look like.
     */
    public ItemStack display(CustomItemRegistry registry) {
        if (customId != null) {
            ItemStack built = registry == null ? null : registry.create(customId);
            return built != null ? built : new ItemStack(Material.BARRIER);
        }
        if (exact != null) {
            return exact.clone();
        }
        if (tag != null) {
            Tag<Material> resolved = resolveTag();
            if (resolved != null) {
                for (Material m : resolved.getValues()) {
                    if (m.isItem()) {
                        return new ItemStack(m);
                    }
                }
            }
            return new ItemStack(Material.BARRIER);
        }
        return new ItemStack(material);
    }

    /** Display without a registry: custom ingredients render as a barrier. */
    public ItemStack display() {
        return display(null);
    }

    /**
     * The Bukkit choice this ingredient matches with.
     *
     * <p>A custom ingredient resolves its id to a freshly built item and matches on the
     * {@code cb_item} stamp where Paper supports it — see {@link CustomItemChoice} for why
     * that is preferred over a whole-component exact match. An unresolvable id throws, which
     * {@link RecipeRegistry#register} turns into a logged "recipe is invalid" rather than a
     * silently missing recipe.
     */
    public RecipeChoice toChoice(CustomItemRegistry registry) {
        if (customId != null) {
            ItemStack built = registry == null ? null : registry.create(customId);
            if (built == null) {
                throw new IllegalArgumentException("unknown custom item '" + customId + "'");
            }
            return CustomItemChoice.forCustomItem(customId, built);
        }
        if (exact != null) {
            return CustomItemChoice.exact(exact);
        }
        if (tag != null) {
            Tag<Material> resolved = resolveTag();
            if (resolved == null) {
                throw new IllegalArgumentException("unknown item tag " + tag);
            }
            return new RecipeChoice.MaterialChoice(resolved);
        }
        return new RecipeChoice.MaterialChoice(material);
    }

    private Tag<Material> resolveTag() {
        return Bukkit.getTag(Tag.REGISTRY_ITEMS, tag, Material.class);
    }

    /** Short human label for lore: "Exact: Sharp Sword", "#minecraft:wool", "Oak Log". */
    public String label() {
        if (customId != null) {
            return "Custom: " + customId;
        }
        if (exact != null) {
            return "Exact: " + Items.describe(exact);
        }
        if (tag != null) {
            return "#" + tag.asString();
        }
        return Items.prettyMaterial(material);
    }

    @Override
    public String toString() {
        return label().toLowerCase(Locale.ROOT);
    }
}
