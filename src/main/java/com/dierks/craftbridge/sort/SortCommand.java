package com.dierks.craftbridge.sort;

import com.dierks.craftbridge.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** {@code /sort}, {@code /sort settings}, {@code /sort debug}. */
public final class SortCommand implements TabExecutor {

    private final SortFeature feature;
    private final Set<UUID> debugging;

    public SortCommand(SortFeature feature, Set<UUID> debugging) {
        this.feature = feature;
        this.debugging = debugging;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.msg("<red>Players only."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "settings", "setting", "options" -> new SortSettingsMenu(feature, player).open(player);
            case "debug" -> {
                if (debugging.remove(player.getUniqueId())) {
                    player.sendMessage(Text.msg("<gray>Click debugging <red>off<gray>."));
                } else {
                    debugging.add(player.getUniqueId());
                    player.sendMessage(Text.msg("<gray>Click debugging <green>on<gray>: open a chest and click outside it; "
                            + "the raw click is printed here so you can see what your client sends."));
                }
            }
            default -> feature.sortOpenInventory(player, player.getOpenInventory());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("settings", "debug");
        }
        return List.of();
    }
}
