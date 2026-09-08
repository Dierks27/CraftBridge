package com.dierks.craftbridge.command;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

/** {@code /craftbridge reload|version}. */
public final class CraftBridgeCommand implements TabExecutor {

    private final CraftBridgePlugin plugin;

    public CraftBridgeCommand(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Text.msg("<gray>/craftbridge reload <dark_gray>- reload config and features"));
            sender.sendMessage(Text.msg("<gray>/craftbridge version <dark_gray>- show version"));
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(Text.msg("<green>Reloaded. <gray>Enabled: " + plugin.enabledFeatureNames()));
            }
            case "version" -> sender.sendMessage(Text.msg("<gray>v" + plugin.getPluginMeta().getVersion()
                    + " <dark_gray>| <gray>" + plugin.enabledFeatureNames()));
            default -> sender.sendMessage(Text.msg("<red>Unknown subcommand."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "version");
        }
        return List.of();
    }
}
