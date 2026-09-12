package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.items.CustomItemDef;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The custom-item list: every definition in {@code custom-items.yml}, with the same
 * paged-list shape, colours and click grammar as {@link RecipeBrowseMenu} so the two read as
 * one admin surface.
 */
public final class CustomItemBrowseMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    @Override
    protected String requiredPermission() {
        return RecipeFeature.ADMIN_PERMISSION;
    }

    private final RecipeFeature feature;
    private final Player player;
    private int page;

    public CustomItemBrowseMenu(RecipeFeature feature, Player player, int page) {
        this.feature = feature;
        this.player = player;
        this.page = page;
        init(54, Text.item("<dark_green>Custom items"));
    }

    @Override
    protected void build() {
        List<CustomItemDef> defs = new ArrayList<>(feature.customItems().all());
        int pages = Math.max(1, (defs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < defs.size(); i++) {
            CustomItemDef def = defs.get(from + i);
            set(i, entryIcon(def), e -> onEntryClick(def, e.getClick()));
        }
        if (page > 0) {
            set(45, Items.icon(Material.ARROW, "<yellow>Previous page"), e -> {
                page--;
                refresh();
            });
        }
        set(48, Items.icon(Material.NETHER_STAR, "<green>New custom item",
                "Define a vanilla item with a name,",
                "lore and a CraftBridge tag that",
                "recipes can match on."), e -> {
            new CustomItemEditorMenu(feature, player, null).open(player);
        });
        set(49, Items.icon(Material.BOOK, "<aqua>What these are",
                "A custom item is a normal item with a",
                "name, lore and a hidden CraftBridge tag.",
                "No resource pack, so Bedrock players see",
                "and use them exactly like everyone else.",
                "",
                "Recipes match the tag, not the name, so a",
                "renamed vanilla item cannot stand in for one.",
                "",
                "<dark_gray>page " + (page + 1) + "/" + pages), null);
        if (page < pages - 1) {
            set(52, Items.icon(Material.ARROW, "<yellow>Next page"), e -> {
                page++;
                refresh();
            });
        }
        set(53, Icons.back(), e -> new RecipeMainMenu(feature, player).open(player));
        fill(Icons.filler());
    }

    private ItemStack entryIcon(CustomItemDef def) {
        ItemStack icon = feature.customItems().create(def, 1);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(Text.item("<dark_gray>id: " + def.id()));
            lore.add(Text.item("<dark_gray>base: " + Items.prettyMaterial(def.base())));
            int used = usedBy(def.id()).size();
            lore.add(Text.item(used == 0
                    ? "<dark_gray>not used by any recipe"
                    : "<dark_gray>used by " + used + " recipe" + (used == 1 ? "" : "s")));
            lore.add(Text.item(""));
            lore.add(Text.item("<yellow>Click <gray>to edit"));
            lore.add(Text.item("<yellow>Shift-click <gray>to get one"));
            lore.add(Text.item("<yellow>Right-click <gray>to delete"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** Recipe ids that reference this custom item, so deleting one can say what it breaks. */
    private List<String> usedBy(String id) {
        List<String> out = new ArrayList<>();
        for (CustomRecipe recipe : feature.store().all().values()) {
            if (recipe.customItemIds().contains(id)) {
                out.add(recipe.id());
            }
        }
        return out;
    }

    private void onEntryClick(CustomItemDef def, ClickType click) {
        if (click.isShiftClick()) {
            ItemStack give = feature.customItems().create(def, 1);
            for (ItemStack left : player.getInventory().addItem(give).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
            player.sendMessage(Text.msg("<green>Gave you one <white>" + def.id() + "<green>."));
            return;
        }
        if (click.isRightClick()) {
            List<String> used = usedBy(def.id());
            String[] detail = used.isEmpty()
                    ? new String[]{"No recipe uses this item."}
                    : new String[]{"<red>" + used.size() + " recipe(s) use it:",
                    "<gray>" + String.join(", ", used),
                    "<red>They will stop registering."};
            new com.dierks.craftbridge.gui.ConfirmMenu(player,
                    "Delete custom item",
                    "<red>Delete " + def.id() + "?",
                    detail,
                    () -> {
                        List<String> broken = feature.deleteItem(def.id());
                        player.sendMessage(Text.msg("<green>Deleted custom item <white>" + def.id() + "<green>."
                                + (broken.isEmpty() ? "" : " <gold>Now broken: <white>" + String.join(", ", broken))));
                        new CustomItemBrowseMenu(feature, player, page).open(player);
                    },
                    () -> new CustomItemBrowseMenu(feature, player, page).open(player)).open(player);
            return;
        }
        new CustomItemEditorMenu(feature, player, def.id()).open(player);
    }
}
