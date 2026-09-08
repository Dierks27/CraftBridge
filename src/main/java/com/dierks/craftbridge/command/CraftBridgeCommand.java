package com.dierks.craftbridge.command;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Text;
import com.dierks.craftbridge.workbench.WorkbenchFeature;
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
            sender.sendMessage(Text.msg("<gray>/craftbridge workbench <dark_gray>- Linked Workbench tools (give, list, refresh, display)"));
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(Text.msg("<green>Reloaded. <gray>Enabled: " + plugin.enabledFeatureNames()));
            }
            case "version" -> sender.sendMessage(Text.msg("<gray>v" + plugin.getPluginMeta().getVersion()
                    + " <dark_gray>| <gray>" + plugin.enabledFeatureNames()));
            case "workbench", "wb" -> {
                WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
                if (workbench == null) {
                    sender.sendMessage(Text.msg("<red>The Linked Workbench feature is disabled in config.yml."));
                } else {
                    workbench.command(sender, args);
                }
            }
            default -> sender.sendMessage(Text.msg("<red>Unknown subcommand."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "version", "workbench");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("workbench")) {
            return List.of("give", "list", "refresh", "display");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("workbench") && args[1].equalsIgnoreCase("display")) {
            return List.of("scale", "x", "y", "z", "yaw", "transform");
        }
        return List.of();
    }
}
