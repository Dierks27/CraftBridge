package com.dierks.craftbridge.items;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code custom_model_data} string a resource pack selects a custom item's look on.
 *
 * <p>Every custom item CraftBridge builds carries {@code craftbridge:item/<id>} at index 0 of
 * its {@code minecraft:custom_model_data} strings, the same pattern
 * {@link com.dierks.craftbridge.workbench.WorkbenchItems#modelItem} uses for the Linked
 * Workbench and the Combo Chest. A pack with art for the id draws it; without the pack, or
 * with no art for that id, the client falls back to the base item, so the tag costs nothing
 * where it is not used. Identity still lives in the {@code cb_item} stamp, never here.
 *
 * <p>The {@code craftbridge:item/} prefix is what keeps the two namespaces apart: the block
 * strings {@code craftbridge:linked_workbench} and {@code craftbridge:combo_chest} have no
 * {@code /}, and item ids ({@link CustomItemIds}) cannot contain one, so no custom item id —
 * not even one named {@code linked_workbench} — can ever produce a block's string.
 *
 * <p>Bukkit-free so the decision "does this stack need its tag, and what do its strings become"
 * can be unit tested; {@link CustomItemRegistry} applies it to real stacks.
 */
public final class ModelTags {

    /** Namespace and path prefix of every custom item's model tag. */
    public static final String PREFIX = "craftbridge:item/";

    private ModelTags() {
    }

    /** {@code burned_zombie_flesh} -> {@code craftbridge:item/burned_zombie_flesh}. */
    public static String of(String id) {
        return PREFIX + id;
    }

    /**
     * The custom-model-data strings with {@code tag} at index 0, or null when {@code strings}
     * already starts with it (nothing to change).
     *
     * <p>Only index 0 is ours. A missing tag is added; a wrong one is replaced in place, and
     * every other entry stays where it was, so whatever another plugin or a hand-made pack put
     * after it keeps its index. The input is never modified.
     */
    /**
     * A {@code custom_model_data} component after a refresh: {@code strings} with the tag at
     * index 0, and the floats, flags and colours exactly as they were.
     */
    public record Refreshed<F, B, C>(List<F> floats, List<B> flags, List<String> strings, List<C> colors) {
    }

    /**
     * The whole component after a refresh, or null when its strings already start with
     * {@code tag}. Generic over the float, flag and colour types so it is tested without a server.
     */
    public static <F, B, C> Refreshed<F, B, C> refreshed(List<F> floats, List<B> flags, List<String> strings,
                                                         List<C> colors, String tag) {
        List<String> tagged = withTag(strings, tag);
        return tagged == null ? null : new Refreshed<>(List.copyOf(floats), List.copyOf(flags), tagged, List.copyOf(colors));
    }

    public static List<String> withTag(List<String> strings, String tag) {
        if (strings == null || strings.isEmpty()) {
            return List.of(tag);
        }
        if (tag.equals(strings.get(0))) {
            return null;
        }
        List<String> out = new ArrayList<>(strings);
        out.set(0, tag);
        return out;
    }
}
