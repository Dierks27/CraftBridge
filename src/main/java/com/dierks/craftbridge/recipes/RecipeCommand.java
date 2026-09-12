package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.recipes.gui.RecipeMainMenu;
import com.dierks.craftbridge.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /recipe} opens the GUI. The subcommands are console-friendly fallbacks and are
 * deliberately not advertised in-game: {@code reload}, {@code remove <id>},
 * {@code import starter}, {@code list}, {@code items}.
 */
public final class RecipeCommand implements TabExecutor {

    private final RecipeFeature feature;

    public RecipeCommand(RecipeFeature feature) {
        this.feature = feature;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(RecipeFeature.ADMIN_PERMISSION)) {
            sender.sendMessage(com.dierks.craftbridge.util.Text.msg("<red>You do not have permission to do that."));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new RecipeMainMenu(feature, player).open(player);
            } else {
                sender.sendMessage(Text.msg("<gray>Console: /recipe list | items | reload | remove <id> | import starter"));
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> sender.sendMessage(Text.msg("<green>Reloaded recipes.yml: <white>" + feature.reload()
                    + "<green> active recipe(s)."));
            case "remove", "delete" -> {
                if (args.length < 2) {
                    sender.sendMessage(Text.msg("<red>Usage: /recipe remove <id>"));
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!feature.store().contains(id)) {
                    sender.sendMessage(Text.msg("<red>No recipe with id '" + id + "'."));
                    return true;
                }
                feature.delete(id);
                sender.sendMessage(Text.msg("<green>Deleted recipe <white>" + id + "<green>."));
            }
            case "import" -> {
                List<String> added = feature.importStarter();
                sender.sendMessage(Text.msg(added.isEmpty()
                        ? "<gray>Starter pack: nothing new to import."
                        : "<green>Imported <white>" + added.size() + "<green> starter recipe(s): <gray>" + String.join(", ", added)));
            }
            case "list" -> {
                if (feature.store().all().isEmpty()) {
                    sender.sendMessage(Text.msg("<gray>No custom recipes yet."));
                }
                for (CustomRecipe r : feature.store().all().values()) {
                    sender.sendMessage(Text.msg((r.enabled() ? "<green>● " : "<red>○ ") + "<white>" + r.id()
                            + " <dark_gray>(" + r.kind().token() + ") <gray>→ "
                            + com.dierks.craftbridge.util.Items.describe(r.result()) + " x" + r.result().getAmount()));
                }
            }
            case "items" -> {
                if (sender instanceof Player player) {
                    new com.dierks.craftbridge.recipes.gui.CustomItemBrowseMenu(feature, player, 0).open(player);
                    return true;
                }
                if (feature.customItems().isEmpty()) {
                    sender.sendMessage(Text.msg("<gray>No custom items defined."));
                }
                for (com.dierks.craftbridge.items.CustomItemDef def : feature.customItems().all()) {
                    sender.sendMessage(Text.msg("<white>" + def.id() + " <dark_gray>("
                            + def.base().name().toLowerCase(Locale.ROOT) + ") <gray>" + def.name()));
                }
            }
            default -> sender.sendMessage(Text.msg("<gray>/recipe opens the recipe menu."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("items");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {
            return new ArrayList<>(feature.store().all().keySet());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return List.of("starter");
        }
        return List.of();
    }
}
