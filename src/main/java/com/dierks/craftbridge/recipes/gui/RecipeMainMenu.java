package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /recipe}: New Recipe, Browse Recipes, Import Starter Pack, Reload. */
public final class RecipeMainMenu extends Menu {

    @Override
    protected String requiredPermission() {
        return com.dierks.craftbridge.recipes.RecipeFeature.ADMIN_PERMISSION;
    }

    private final RecipeFeature feature;
    private final Player player;

    public RecipeMainMenu(RecipeFeature feature, Player player) {
        this.feature = feature;
        this.player = player;
        init(27, Text.item("<dark_green>Custom recipes"));
    }

    @Override
    protected void build() {
        int count = feature.store().all().size();
        set(10, Items.icon(Material.CRAFTING_TABLE, "<green>New recipe",
                "Place real items in a 3x3 grid and a result,",
                "then Save. Your items come back when you",
                "leave the editor."), e -> new RecipeEditorMenu(feature, player, null).open(player));
        set(12, Items.icon(Material.BOOK, "<aqua>Browse recipes",
                count + " custom recipe(s).",
                "Left-click to edit, right-click to enable/disable,",
                "shift-right-click to delete."), e -> new RecipeBrowseMenu(feature, player, 0).open(player));
        set(14, Items.icon(Material.CHEST, "<gold>Import starter pack",
                "Adds the bundled peaceful-mode recipes",
                "(string, gunpowder, ender pearls, blaze rods...)",
                "that are not already present. Tune them",
                "afterwards in Browse or in recipes.yml."), e -> {
            List<String> added = feature.importStarter();
            player.sendMessage(Text.msg(added.isEmpty()
                    ? "<gray>Starter pack: nothing new to import."
                    : "<green>Imported <white>" + added.size() + "<green> recipe(s): <gray>" + String.join(", ", added)));
            refresh();
        });
        set(16, Items.icon(Material.REPEATER, "<yellow>Reload recipes.yml",
                "Re-read the file (after hand edits) and",
                "re-register everything."), e -> {
            int active = feature.reload();
            player.sendMessage(Text.msg("<green>Reloaded: <white>" + active + "<green> active recipe(s)."));
            refresh();
        });
        set(22, Items.icon(Material.NETHER_STAR, "<light_purple>Custom items",
                feature.customItems().all().size() + " defined.",
                "Vanilla items with a name, lore and a",
                "CraftBridge tag recipes can match on.",
                "No resource pack, so Bedrock players",
                "see and use them too."), e -> new CustomItemBrowseMenu(feature, player, 0).open(player));
        set(26, Icons.close(), e -> player.closeInventory());
        fill(Icons.filler());
    }
}
