package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.util.Locale;

/**
 * One crafting ingredient. Three flavours:
 * <ul>
 *   <li><b>material</b> — any item of that material (what the editor produces by default);</li>
 *   <li><b>exact</b> — the exact item (name, enchantments, components) placed in the editor;</li>
 *   <li><b>tag</b> — an item tag such as {@code minecraft:wool} (hand-written recipes only).</li>
 * </ul>
 */
public final class Ingredient {

    private final Material material;
    private final ItemStack exact;
    private final NamespacedKey tag;

    private Ingredient(Material material, ItemStack exact, NamespacedKey tag) {
        this.material = material;
        this.exact = exact;
        this.tag = tag;
    }

    public static Ingredient ofMaterial(Material material) {
        return new Ingredient(material, null, null);
    }

    public static Ingredient ofExact(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        return new Ingredient(one.getType(), one, null);
    }

    public static Ingredient ofTag(NamespacedKey tag) {
        return new Ingredient(null, null, tag);
    }

    public boolean isExact() {
        return exact != null;
    }

    public boolean isTag() {
        return tag != null;
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
        if (isTag() || other.isTag()) {
            return isTag() && other.isTag() && tag.equals(other.tag);
        }
        if (isExact() != other.isExact()) {
            return false;
        }
        return isExact() ? exact.isSimilar(other.exact) : material == other.material;
    }

    /** The item shown in GUIs / used for conflict matching. */
    public ItemStack display() {
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

    /**
     * The constructor is deprecated-for-removal in favour of {@code RecipeChoice.exactChoice}
     * on newer 26.2 builds; it still exists on the pinned build, and the static factory may not,
     * so the constructor is the safer choice until the pin is bumped.
     */
    @SuppressWarnings("removal")
    public RecipeChoice toChoice() {
        if (exact != null) {
            return new RecipeChoice.ExactChoice(exact.clone());
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
