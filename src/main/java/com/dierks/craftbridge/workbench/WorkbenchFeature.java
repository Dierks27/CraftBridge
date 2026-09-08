package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.jei.JeiTransferFeature;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Feature 2: the Linked Workbench (a crafting table that pulls from nearby chests). */
public final class WorkbenchFeature implements CraftBridgePlugin.Feature {


    private final CraftBridgePlugin plugin;
    private final WorkbenchStore store;
    private final WorkbenchItems items;
    private final DisplayManager displays;
    private final StorageScanner scanner;
    private final SessionManager sessions;
    private WorkbenchListener listener;
    private PhantomManager phantoms;
    private final java.util.Set<BlockKind> recipesRegistered = java.util.EnumSet.noneOf(BlockKind.class);

    public WorkbenchFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.store = new WorkbenchStore(plugin);
        this.items = new WorkbenchItems(plugin);
        this.displays = new DisplayManager(plugin, store, items);
        this.scanner = new StorageScanner(plugin);
        this.scanner.terminals(() -> store.locationsOf(BlockKind.COMBO_CHEST));
        this.sessions = new SessionManager(plugin);
    }

    @Override
    public String name() {
        return "linked-workbench";
    }

    @Override
    public void enable() {
        store.load();
        int[] swept = displays.sweep();
        plugin.getLogger().info("Linked Workbench: " + store.all().size() + " placed; startup sweep removed "
                + swept[0] + " orphaned display(s), respawned " + swept[1] + ".");
        for (BlockKind kind : BlockKind.values()) {
            registerRecipe(kind);
        }
        phantoms = PhantomManager.create(plugin, this);
        sessions.setPhantoms(phantoms);
        if (phantoms != null) {
            plugin.getLogger().info("Linked Workbench: phantom inventory slots enabled (JEI sees nearby storage).");
        }
        listener = new WorkbenchListener(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        JeiTransferFeature jei = plugin.feature(JeiTransferFeature.class);
        if (jei != null) {
            jei.addListener(new LinkedTransferBridge(plugin, this));
            plugin.getLogger().info("Linked Workbench: JEI shift-[+] will top up sets from nearby storage.");
        }
    }

    @Override
    public void disable() {
        sessions.endAll();
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        for (BlockKind kind : recipesRegistered) {
            Bukkit.removeRecipe(kind.recipeKey());
        }
        recipesRegistered.clear();
    }

    public WorkbenchStore store() {
        return store;
    }

    public WorkbenchItems items() {
        return items;
    }

    public DisplayManager displays() {
        return displays;
    }

    public StorageScanner scanner() {
        return scanner;
    }

    public SessionManager sessions() {
        return sessions;
    }

    /** Null when phantom slots are disabled in config or the packet bridge failed to load. */
    public PhantomManager phantoms() {
        return phantoms;
    }

    /** Hand out place-items: {@code kindName} is "workbench" or "combochest". */
    public boolean give(CommandSender sender, Player target, String kindName, int amount) {
        BlockKind kind = BlockKind.byId(kindName);
        if (kind == null) {
            sender.sendMessage(Text.msg("<red>Unknown item '" + kindName + "'. Try: workbench, combochest"));
            return false;
        }
        ItemStack item = items.placeItem(kind, amount);
        for (ItemStack left : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        sender.sendMessage(Text.msg("<green>Gave " + amount + "x " + Items.describe(item) + " to " + target.getName() + "."));
        return true;
    }

    /** Turn {@code block} into a {@code kind} block facing {@code player}. */
    public void place(Block block, Player player, BlockKind kind) {
        block.setType(kind.block());
        float yaw = snapYaw(player.getLocation().getYaw() + 180f);
        WorkbenchRecord record = new WorkbenchRecord(kind, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                null, player.getUniqueId(), yaw);
        record = displays.spawn(record);
        store.put(record);
    }

    /** Forget a workbench, remove its display and (optionally) drop the head item at the block. */
    public void unplace(Block block, boolean dropItem) {
        WorkbenchRecord record = store.remove(WorkbenchRecord.keyOf(block));
        if (record == null) {
            return;
        }
        displays.remove(record.display());
        if (dropItem) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), items.placeItem(record.kind(), 1));
        }
    }

    /** Yaw snapped to the nearest 90 degrees, normalised to [0, 360). */
    static float snapYaw(float yaw) {
        float snapped = Math.round(yaw / 90f) * 90f;
        snapped %= 360f;
        return snapped < 0 ? snapped + 360f : snapped;
    }

    private void registerRecipe(BlockKind kind) {
        Bukkit.removeRecipe(kind.recipeKey());
        recipesRegistered.remove(kind);
        if (!plugin.config().recipeEnabled(kind)) {
            return;
        }
        List<String> shape = plugin.config().recipeShape(kind);
        Map<Character, Material> ingredients = plugin.config().recipeIngredients(kind);
        try {
            ShapedRecipe recipe = new ShapedRecipe(kind.recipeKey(), items.placeItem(kind, 1));
            recipe.shape(shape.toArray(new String[0]));
            for (Map.Entry<Character, Material> e : ingredients.entrySet()) {
                if (shape.stream().anyMatch(row -> row.indexOf(e.getKey()) >= 0)) {
                    recipe.setIngredient(e.getKey(), new RecipeChoice.MaterialChoice(e.getValue()));
                }
            }
            if (Bukkit.addRecipe(recipe)) {
                recipesRegistered.add(kind);
            } else {
                plugin.getLogger().warning(kind.displayName() + " recipe was rejected by the server.");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning(kind.displayName() + " recipe in config.yml is invalid: " + ex.getMessage());
        }
    }

    // ---- /craftbridge workbench|combochest ... ---------------------------------------

    public void command(CommandSender sender, String[] args, BlockKind kind) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String root = "/craftbridge " + (kind == BlockKind.COMBO_CHEST ? "combochest" : "workbench");
        switch (sub) {
            case "give" -> {
                Player target = args.length > 2 ? Bukkit.getPlayer(args[2]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Text.msg("<red>Player not found (usage: /craftbridge workbench give [player] [amount])."));
                    return;
                }
                int amount = 1;
                if (args.length > 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) {
                        // keep 1
                    }
                }
                give(sender, target, kind.id(), amount);
            }
            case "list" -> {
                sender.sendMessage(Text.msg("<gray>" + store.all().size() + " placed block(s):"));
                for (WorkbenchRecord r : store.all()) {
                    boolean displayOk = r.display() != null && Bukkit.getEntity(r.display()) instanceof ItemDisplay;
                    sender.sendMessage(Text.msg("<white>" + r.kind().displayName() + " <gray>" + r.world() + " " + r.x() + "," + r.y() + "," + r.z()
                            + " <dark_gray>yaw " + (int) r.yaw() + " <gray>display " + (displayOk ? "<green>ok" : "<red>missing/unloaded")));
                }
            }
            case "refresh" -> sender.sendMessage(Text.msg("<green>Respawned " + displays.refreshAll() + " display(s)."));
            case "display" -> tune(sender, args, kind, root);
            default -> {
                sender.sendMessage(Text.msg("<gray>" + root + " give [player] [amount]"));
                sender.sendMessage(Text.msg("<gray>" + root + " list | refresh"));
                sender.sendMessage(Text.msg("<gray>" + root + " display <scale|x|y|z|yaw|transform> <value>"));
            }
        }
    }

    /** Live-tune the display look of one kind: writes config.yml and respawns every loaded display. */
    private void tune(CommandSender sender, String[] args, BlockKind kind, String root) {
        if (args.length < 4) {
            sender.sendMessage(Text.msg("<gray>Current: " + plugin.config().displayFor(kind)));
            sender.sendMessage(Text.msg("<gray>" + root + " display <scale|x|y|z|yaw|transform> <value>"));
            return;
        }
        String what = args[2].toLowerCase(Locale.ROOT);
        String value = args[3];
        String base = kind.configSection() + ".display.";
        String path = switch (what) {
            case "scale" -> base + "scale";
            case "x" -> base + "offset-x";
            case "y" -> base + "offset-y";
            case "z" -> base + "offset-z";
            case "yaw" -> base + "yaw-offset";
            case "transform" -> base + "transform";
            default -> null;
        };
        if (path == null) {
            sender.sendMessage(Text.msg("<red>Unknown property. Use scale, x, y, z, yaw or transform."));
            return;
        }
        if (what.equals("transform")) {
            try {
                ItemDisplay.ItemDisplayTransform.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                sender.sendMessage(Text.msg("<red>Transform must be one of NONE, HEAD, FIXED, GUI, GROUND, ..."));
                return;
            }
            plugin.getConfig().set(path, value.toUpperCase(Locale.ROOT));
        } else {
            try {
                plugin.getConfig().set(path, Double.parseDouble(value));
            } catch (NumberFormatException ex) {
                sender.sendMessage(Text.msg("<red>'" + value + "' is not a number."));
                return;
            }
        }
        plugin.saveConfig();
        int n = displays.refreshAll();
        sender.sendMessage(Text.msg("<green>Set " + kind.displayName() + " " + what + " = " + value + " and respawned " + n
                + " display(s). Now: " + plugin.config().displayFor(kind)));
    }

    public static String describeItem(ItemStack stack) {
        return Items.describe(stack);
    }
}
