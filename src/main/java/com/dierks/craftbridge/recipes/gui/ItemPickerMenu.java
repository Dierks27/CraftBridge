package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.sort.SortCategoryRules;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import com.dierks.craftbridge.workbench.BlockKind;
import com.dierks.craftbridge.workbench.WorkbenchFeature;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Pick an item without owning it: a paginated browser over every item the server knows,
 * plus the custom items CraftBridge itself defines. Opened from an empty slot in the recipe
 * editor, so an admin can define a recipe for something unobtainable without switching to
 * creative and without the picker ever handing out a real item.
 *
 * <p>Chest GUI only (no anvil text input), so it works the same on Geyser/Bedrock.
 */
public final class ItemPickerMenu extends Menu {

    @Override
    protected String requiredPermission() {
        return com.dierks.craftbridge.recipes.RecipeFeature.ADMIN_PERMISSION;
    }

    private static final int PAGE_SIZE = 45;

    enum Filter {
        ALL("All", Material.CHEST),
        BLOCKS("Blocks", Material.STONE),
        TOOLS_ARMOR("Tools & armor", Material.IRON_PICKAXE),
        FOOD("Food", Material.BREAD),
        MISC("Misc", Material.STRING),
        CUSTOM("Custom items", Material.NETHER_STAR);

        final String title;
        final Material icon;

        Filter(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    private final RecipeFeature feature;
    private final Player player;
    private final Consumer<ItemStack> onPick;
    private final Runnable onBack;
    private final Runnable onClosed;
    private final SortCategoryRules categories = SortCategoryRules.defaults();
    private Filter filter = Filter.ALL;
    private int page;

    public ItemPickerMenu(RecipeFeature feature, Player player, String what,
                          Consumer<ItemStack> onPick, Runnable onBack, Runnable onClosed) {
        this.feature = feature;
        this.player = player;
        this.onPick = onPick;
        this.onBack = onBack;
        this.onClosed = onClosed;
        init(54, Text.item("<dark_aqua>Pick an item <gray>— " + what));
    }

    private Filter filterOf(Material type) {
        String name = categories.nameOf(categories.categoryOf(type.name(), type.isBlock(), type.isEdible()))
                .toLowerCase(Locale.ROOT);
        return switch (name) {
            case "blocks" -> Filter.BLOCKS;
            case "tools", "weapons", "armor", "armour" -> Filter.TOOLS_ARMOR;
            case "food" -> Filter.FOOD;
            default -> Filter.MISC;
        };
    }

    /** Custom items this server defines: CraftBridge's blocks and every custom recipe's result. */
    private List<ItemStack> customItems() {
        List<ItemStack> out = new ArrayList<>();
        WorkbenchFeature workbench = feature.plugin().feature(WorkbenchFeature.class);
        if (workbench != null) {
            for (BlockKind kind : BlockKind.values()) {
                out.add(workbench.items().placeItem(kind, 1));
            }
        }
        for (CustomRecipe recipe : feature.store().all().values()) {
            ItemStack result = recipe.result();
            if (!Items.isEmpty(result)) {
                ItemStack copy = result.clone();
                copy.setAmount(1);
                out.add(copy);
            }
        }
        return out;
    }

    private List<ItemStack> entries() {
        if (filter == Filter.CUSTOM) {
            return customItems();
        }
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || material.isAir() || !material.isItem()) {
                continue;
            }
            if (filter == Filter.ALL || filterOf(material) == filter) {
                materials.add(material);
            }
        }
        materials.sort(Comparator.comparing(Items::prettyMaterial));
        List<ItemStack> out = new ArrayList<>(materials.size());
        for (Material material : materials) {
            out.add(new ItemStack(material));
        }
        if (filter == Filter.ALL) {
            out.addAll(0, customItems());
        }
        return out;
    }

    @Override
    protected void build() {
        List<ItemStack> entries = entries();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < entries.size(); i++) {
            ItemStack entry = entries.get(from + i);
            ItemStack icon = entry.clone();
            set(i, icon, e -> {
                ItemStack picked = entry.clone();
                picked.setAmount(1);
                onPick.accept(picked);
            });
        }
        if (page > 0) {
            set(45, Items.icon(Material.ARROW, "<yellow>Previous page"), e -> {
                page--;
                refresh();
            });
        }
        int[] filterSlots = {46, 47, 48, 49, 50, 51};
        Filter[] filters = Filter.values();
        for (int i = 0; i < filters.length && i < filterSlots.length; i++) {
            Filter f = filters[i];
            boolean selected = f == filter;
            ItemStack icon = Items.icon(f.icon, (selected ? "<green>" : "<white>") + f.title,
                    selected ? "<green>Showing" : "<yellow>Click <gray>to show only these",
                    "", "<dark_gray>page " + (page + 1) + "/" + pages);
            Items.glint(icon, selected);
            set(filterSlots[i], icon, e -> {
                filter = f;
                page = 0;
                refresh();
            });
        }
        if (page < pages - 1) {
            set(52, Items.icon(Material.ARROW, "<yellow>Next page"), e -> {
                page++;
                refresh();
            });
        }
        set(53, Icons.back(), e -> onBack.run());
        fill(Icons.filler());
    }

    @Override
    protected void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        // Escape out of the picker must not strand the editor (and the items in it).
        if (onClosed != null) {
            onClosed.run();
        }
    }

    public void openFor() {
        open(player);
    }
}
