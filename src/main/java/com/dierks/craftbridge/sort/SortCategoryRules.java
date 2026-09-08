package com.dierks.craftbridge.sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Ordered category list from config. Pure Java (no Bukkit) so it is unit-testable:
 * callers pass the material name plus the two flags the special tokens need.
 */
public final class SortCategoryRules {

    /** One configured category: a name and the patterns / special tokens that select it. */
    public record Category(String name, List<String> patterns) {
    }

    private record Compiled(String name, List<Pattern> patterns, boolean edible, boolean block, boolean isDefault) {
    }

    private final List<Compiled> categories;

    public SortCategoryRules(List<Category> categories) {
        List<Compiled> compiled = new ArrayList<>();
        for (Category c : categories) {
            List<Pattern> patterns = new ArrayList<>();
            boolean edible = false;
            boolean block = false;
            boolean isDefault = false;
            for (String p : c.patterns()) {
                String trimmed = p.trim();
                switch (trimmed.toLowerCase(Locale.ROOT)) {
                    case "@edible" -> edible = true;
                    case "@block" -> block = true;
                    case "@default" -> isDefault = true;
                    default -> {
                        try {
                            patterns.add(Pattern.compile(trimmed.toUpperCase(Locale.ROOT)));
                        } catch (PatternSyntaxException ex) {
                            // A bad pattern just never matches; reported by the caller via validate().
                        }
                    }
                }
            }
            compiled.add(new Compiled(c.name(), patterns, edible, block, isDefault));
        }
        this.categories = List.copyOf(compiled);
    }

    public static SortCategoryRules defaults() {
        return new SortCategoryRules(List.of(
                new Category("tools", List.of(".*_PICKAXE", ".*_AXE", ".*_SHOVEL", ".*_HOE", "SHEARS",
                        "FLINT_AND_STEEL", "FISHING_ROD", "BRUSH", "SPYGLASS", "COMPASS", "CLOCK", "LEAD",
                        "NAME_TAG", "BUCKET", ".*_BUCKET")),
                new Category("weapons", List.of(".*_SWORD", "BOW", "CROSSBOW", "TRIDENT", "MACE", "ARROW",
                        ".*_ARROW", "SHIELD")),
                new Category("armor", List.of(".*_HELMET", ".*_CHESTPLATE", ".*_LEGGINGS", ".*_BOOTS", "ELYTRA",
                        "TURTLE_HELMET", ".*_HORSE_ARMOR", "WOLF_ARMOR")),
                new Category("food", List.of("@edible")),
                new Category("blocks", List.of("@block")),
                new Category("items", List.of("@default"))));
    }

    public int size() {
        return categories.size();
    }

    public String nameOf(int index) {
        return index >= 0 && index < categories.size() ? categories.get(index).name() : "?";
    }

    /**
     * Index of the first category matching this material, or the {@code @default}
     * category, or the last category if none is marked default.
     */
    public int categoryOf(String materialName, boolean isBlock, boolean isEdible) {
        String upper = materialName.toUpperCase(Locale.ROOT);
        int defaultIndex = -1;
        for (int i = 0; i < categories.size(); i++) {
            Compiled c = categories.get(i);
            if (c.isDefault() && defaultIndex < 0) {
                defaultIndex = i;
            }
            for (Pattern p : c.patterns()) {
                if (p.matcher(upper).matches()) {
                    return i;
                }
            }
            if (c.edible() && isEdible) {
                return i;
            }
            if (c.block() && isBlock) {
                return i;
            }
        }
        return defaultIndex >= 0 ? defaultIndex : Math.max(0, categories.size() - 1);
    }
}
