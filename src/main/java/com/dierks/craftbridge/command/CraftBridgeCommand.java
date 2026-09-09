package com.dierks.craftbridge.command;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Text;
import com.dierks.craftbridge.workbench.BlockKind;
import com.dierks.craftbridge.workbench.PhantomManager;
import com.dierks.craftbridge.workbench.WorkbenchFeature;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /craftbridge reload|version}. */
public final class CraftBridgeCommand implements TabExecutor {

    private final CraftBridgePlugin plugin;

    public CraftBridgeCommand(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    private static final String ADMIN = "craftbridge.admin";
    /** Turning your own storage page changes nothing but your own view, so everyone may. */
    private static final String PAGE = "craftbridge.page";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission(ADMIN)) {
                if (sender.hasPermission(PAGE)) {
                    sender.sendMessage(Text.msg("<gray>/craftbridge page next|prev <dark_gray>- turn the storage page at a Linked Workbench"));
                } else {
                    sender.sendMessage(Text.msg("<red>You do not have permission to do that."));
                }
                return true;
            }
            sender.sendMessage(Text.msg("<gray>/craftbridge page next|prev <dark_gray>- turn the storage page at a Linked Workbench"));
            sender.sendMessage(Text.msg("<gray>/craftbridge reload <dark_gray>- reload config and features"));
            sender.sendMessage(Text.msg("<gray>/craftbridge version <dark_gray>- show version"));
            sender.sendMessage(Text.msg("<gray>/craftbridge give <player> workbench|combochest [amount] <dark_gray>- hand out CraftBridge blocks"));
            sender.sendMessage(Text.msg("<gray>/craftbridge workbench|combochest <dark_gray>- block tools (give, list, refresh, display)"));
            sender.sendMessage(Text.msg("<gray>/craftbridge jei [resync|dump <recipe>] <dark_gray>- recipe sync state, re-send, or inspect one recipe on the wire"));
            return true;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        // Paging is the player's own view of their own inventory, so it needs no permission.
        if (sub.equals("page")) {
            if (!sender.hasPermission(PAGE)) {
                sender.sendMessage(Text.msg("<red>You do not have permission to do that."));
                return true;
            }
            page(sender, args);
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.msg("<red>You do not have permission to do that."));
            return true;
        }
        switch (sub) {
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(Text.msg("<green>Reloaded. <gray>Enabled: " + plugin.enabledFeatureNames()));
            }
            case "version" -> sender.sendMessage(Text.msg("<gray>v" + plugin.getPluginMeta().getVersion()
                    + " <dark_gray>| <gray>" + plugin.enabledFeatureNames()));
            case "give" -> {
                if (args.length < 3) {
                    sender.sendMessage(Text.msg("<red>Usage: /craftbridge give <player> <workbench|combochest> [amount]"));
                    return true;
                }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(Text.msg("<red>Player '" + args[1] + "' is not online."));
                    return true;
                }
                int amount = 1;
                if (args.length > 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) {
                        // keep 1
                    }
                }
                WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
                if (workbench == null) {
                    sender.sendMessage(Text.msg("<red>The Linked Workbench feature is disabled in config.yml."));
                } else {
                    workbench.give(sender, target, args[2], amount);
                }
            }
            case "jei" -> {
                com.dierks.craftbridge.jei.RecipeSyncFeature sync =
                        plugin.feature(com.dierks.craftbridge.jei.RecipeSyncFeature.class);
                if (sync == null) {
                    sender.sendMessage(Text.msg("<red>features.jei-recipe-sync is off in config.yml."));
                } else if (args.length > 2 && args[1].equalsIgnoreCase("dump")) {
                    for (String line : sync.dump(args[2])) {
                        sender.sendMessage(Text.msg("<gray>" + line.replace("<", "\\<")));
                        plugin.getLogger().info("[jei dump] " + line);
                    }
                } else if (args.length > 1 && args[1].equalsIgnoreCase("resync")) {
                    int n = sync.resyncNow();
                    sender.sendMessage(Text.msg("<green>Re-encoded and sent to " + n + " JEI client(s): <gray>" + sync.describe()));
                } else {
                    sender.sendMessage(Text.msg(sync.isActive()
                            ? "<gray>Recipe sync: <white>" + sync.describe() + " <gray>| sent to <white>"
                              + sync.syncedPlayerCount() + "<gray> listening client(s)"
                            : "<red>Recipe sync is not active (the server-internals encoder did not load)."));
                    sender.sendMessage(Text.msg("<dark_gray>/craftbridge jei resync <dark_gray>re-encodes and re-sends to everyone"));
                }
            }
            case "workbench", "wb", "combochest", "combo", "cc" -> {
                WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
                if (workbench == null) {
                    sender.sendMessage(Text.msg("<red>The Linked Workbench feature is disabled in config.yml."));
                } else {
                    BlockKind kind = args[0].toLowerCase(java.util.Locale.ROOT).startsWith("c") ? BlockKind.COMBO_CHEST : BlockKind.WORKBENCH;
                    workbench.command(sender, args, kind);
                }
            }
            default -> sender.sendMessage(Text.msg("<red>Unknown subcommand."));
        }
        return true;
    }

    /** {@code /craftbridge page next|prev|<n>}: the fallback for the phantom page buttons. */
    private void page(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.msg("<red>Only a player at a Linked Workbench can turn the page."));
            return;
        }
        WorkbenchFeature workbench = plugin.feature(WorkbenchFeature.class);
        PhantomManager phantoms = workbench == null ? null : workbench.phantoms();
        if (phantoms == null || !phantoms.has(player)) {
            sender.sendMessage(Text.msg("<red>Open a Linked Workbench first."));
            return;
        }
        int delta = 1;
        if (args.length > 1) {
            String what = args[1].toLowerCase(java.util.Locale.ROOT);
            if (what.startsWith("p") || what.startsWith("b")) {
                delta = -1;
            } else if (!what.startsWith("n")) {
                try {
                    delta = Integer.parseInt(what);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Text.msg("<red>Usage: /craftbridge page next|prev"));
                    return;
                }
            }
        }
        if (!phantoms.turnPage(player, delta)) {
            sender.sendMessage(Text.msg("<gray>Everything in range fits on one page."));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission(ADMIN)) {
                return List.of("page", "reload", "version", "give", "workbench", "combochest", "jei");
            }
            return sender.hasPermission(PAGE) ? List.of("page") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("page")) {
            return List.of("next", "prev");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("jei")) {
            return List.of("status", "resync", "dump");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return null; // player names
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return List.of("workbench", "combochest");
        }
        boolean blockCmd = args[0].equalsIgnoreCase("workbench") || args[0].equalsIgnoreCase("combochest");
        if (args.length == 2 && blockCmd) {
            return List.of("give", "list", "refresh", "display");
        }
        if (args.length == 3 && blockCmd && args[1].equalsIgnoreCase("display")) {
            return List.of("scale", "x", "y", "z", "yaw", "transform");
        }
        return List.of();
    }
}
