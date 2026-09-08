package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.ConfirmMenu;
import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.Ingredient;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.recipes.RecipeShape;
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
import java.util.Map;

/**
 * Paginated list of custom recipes, 45 per page. Each entry is the result item with the
 * shape and legend in its lore. Left-click edits, right-click toggles enabled,
 * shift-right-click asks to delete.
 */
public final class RecipeBrowseMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final RecipeFeature feature;
    private final Player player;
    private int page;

    public RecipeBrowseMenu(RecipeFeature feature, Player player, int page) {
        this.feature = feature;
        this.player = player;
        this.page = page;
        init(54, Text.item("<dark_green>Custom recipes <dark_gray>- browse"));
    }

    @Override
    protected void build() {
        List<CustomRecipe> all = new ArrayList<>(feature.store().all().values());
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            CustomRecipe recipe = all.get(from + i);
            set(i, entryIcon(recipe), e -> onEntryClick(recipe, e.getClick()));
        }
        if (page > 0) {
            set(45, Items.icon(Material.ARROW, "<yellow>Previous page"), e -> {
                page--;
                refresh();
            });
        }
        set(49, Items.icon(Material.OAK_DOOR, "<yellow>Back to menu",
                "Page " + (page + 1) + " of " + pages + " - " + all.size() + " recipe(s)"),
                e -> new RecipeMainMenu(feature, player).open(player));
        if (page < pages - 1) {
            set(53, Items.icon(Material.ARROW, "<yellow>Next page"), e -> {
                page++;
                refresh();
            });
        }
        fill(Icons.filler());
    }

    private void onEntryClick(CustomRecipe recipe, ClickType click) {
        switch (click) {
            case SHIFT_RIGHT -> new ConfirmMenu(player, "<red>Delete recipe?",
                    "<red>Delete <white>" + recipe.id() + "<red>?",
                    new String[]{"This removes it from recipes.yml.", "Items are not affected."},
                    () -> {
                        feature.delete(recipe.id());
                        player.sendMessage(Text.msg("<green>Deleted recipe <white>" + recipe.id() + "<green>."));
                        new RecipeBrowseMenu(feature, player, page).open(player);
                    },
                    () -> new RecipeBrowseMenu(feature, player, page).open(player)).open(player);
            case RIGHT -> {
                feature.setEnabled(recipe.id(), !recipe.enabled());
                refresh();
            }
            case LEFT, SHIFT_LEFT -> new RecipeEditorMenu(feature, player, recipe.id()).open(player);
            default -> {
                // ignore
            }
        }
    }

    /** The result item, with id/shape/legend lore. A display copy: never handed to the player. */
    static ItemStack entryIcon(CustomRecipe recipe) {
        ItemStack icon = recipe.result();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            icon = new ItemStack(Material.BARRIER);
            meta = icon.getItemMeta();
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Text.item("<dark_gray>id: <gray>" + recipe.id()
                + "  <dark_gray>|<gray> " + (recipe.shaped() ? "shaped" : "shapeless")
                + "  <dark_gray>|" + (recipe.enabled() ? " <green>enabled" : " <red>disabled")));
        lore.add(Text.item("<gray>Makes <white>" + recipe.result().getAmount() + "x " + Items.describe(recipe.result())));
        lore.add(Component.empty());
        for (String line : describeLayout(recipe)) {
            lore.add(Text.item(line));
        }
        lore.add(Component.empty());
        lore.add(Text.item("<yellow>Left-click <gray>edit"));
        lore.add(Text.item("<yellow>Right-click <gray>" + (recipe.enabled() ? "disable" : "enable")));
        lore.add(Text.item("<yellow>Shift-right-click <gray>delete"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(com.dierks.craftbridge.util.Keys.GUI_BUTTON,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        icon.setItemMeta(meta);
        return icon;
    }

    /** Shape rows in monospace-ish letters plus a legend, e.g. "A G A" / "A = Amethyst Shard". */
    static List<String> describeLayout(CustomRecipe recipe) {
        List<String> lines = new ArrayList<>();
        RecipeShape.Shape<Ingredient> shape = recipe.shapeForDisplay();
        if (recipe.shaped()) {
            for (String row : shape.rows()) {
                StringBuilder sb = new StringBuilder("<white>");
                for (char ch : row.toCharArray()) {
                    sb.append(ch == ' ' ? "<dark_gray>·<white>" : String.valueOf(ch)).append(' ');
                }
                lines.add(sb.toString().trim());
            }
        } else {
            lines.add("<gray>any arrangement of:");
        }
        for (Map.Entry<Character, Ingredient> e : shape.legend().entrySet()) {
            int count = 0;
            for (Ingredient ing : recipe.asGrid()) {
                if (ing != null && e.getValue().sameAs(ing)) {
                    count++;
                }
            }
            lines.add("<white>" + e.getKey() + " <dark_gray>= <gray>" + count + "x " + e.getValue().label());
        }
        return lines;
    }
}
