package com.dierks.craftbridge.command;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.items.CustomItemChoice;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * <b>Throwaway diagnostic — delete once the Bedrock question is settled.</b>
 *
 * <p>{@code /craftbridge spike} registers two disposable recipes whose input is a
 * PDC-stamped item matched with an exact/predicate choice, and hands the admin one stamped
 * item plus one deliberately unstamped look-alike. It exists to answer, in-game and in about
 * two minutes, the one Phase 0 question that cannot be answered by reading source: whether a
 * Bedrock client through Geyser can craft and smelt these, and whether the plain look-alike
 * wrongly satisfies them.
 *
 * <p>Reading Geyser's source says it should: Bedrock's crafting request is replayed to the
 * Java server as ordinary container clicks, so the choice is tested against the real stack.
 * It also says the Bedrock client will <em>display</em> a result for the plain item, because
 * Bedrock's recipe wire format has no NBT-bearing ingredient descriptor. Both halves need
 * confirming on a real client, and the crafting-table half is expected to look worse than
 * the furnace half.
 *
 * <p>The recipes use their own {@code craftbridge:spike_*} keys and are removed by
 * {@code /craftbridge spike off}, a reload, or a restart, so nothing survives the test.
 */
public final class BedrockSpike {

    private static final NamespacedKey SPIKE_TAG = Keys.key("spike_item");
    private static final NamespacedKey CRAFT_KEY = new NamespacedKey("craftbridge", "spike_craft");
    private static final NamespacedKey COOK_KEY = new NamespacedKey("craftbridge", "spike_cook");
    /** A base with no vanilla smelting recipe, so nothing can shadow the furnace half. */
    private static final Material BASE = Material.ROTTEN_FLESH;

    private final CraftBridgePlugin plugin;
    private boolean active;

    public BedrockSpike(CraftBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** The stamped item the recipes require. */
    public ItemStack stampedItem() {
        ItemStack item = new ItemStack(BASE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<light_purple>Spike Token <dark_gray>(stamped)"));
        meta.lore(Text.lore("Carries craftbridge:spike_item.", "This one should work."));
        meta.getPersistentDataContainer().set(SPIKE_TAG, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A look-alike with the same name and lore but no stamp. If this one crafts, matching is
     * broken — which is the whole point of handing it over alongside the real one.
     */
    public ItemStack lookAlikeItem() {
        ItemStack item = new ItemStack(BASE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<light_purple>Spike Token <dark_gray>(stamped)"));
        meta.lore(Text.lore("Carries craftbridge:spike_item.", "This one should work."));
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isStamped(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(SPIKE_TAG, PersistentDataType.BYTE);
    }

    public void on(CommandSender sender) {
        off(sender, true);
        ItemStack stamped = stampedItem();
        RecipeChoice choice = CustomItemChoice.predicateChoiceAvailable()
                ? predicate(stamped)
                : CustomItemChoice.exact(stamped);

        ShapelessRecipe craft = new ShapelessRecipe(CRAFT_KEY, named(Material.DIAMOND, "<aqua>Spike Craft Output"));
        craft.addIngredient(choice);
        Bukkit.addRecipe(craft);

        FurnaceRecipe cook = new FurnaceRecipe(COOK_KEY, named(Material.EMERALD, "<green>Spike Cook Output"),
                choice, 0.1f, 100);
        Bukkit.addRecipe(cook);

        active = true;
        try {
            Bukkit.updateRecipes();
        } catch (Throwable ignored) {
            // Older builds may not like this mid-session; the recipes still work.
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipes(List.of(CRAFT_KEY, COOK_KEY));
        }
        sender.sendMessage(Text.msg("<green>Spike recipes registered.</green> <gray>Matching mode: <white>"
                + (CustomItemChoice.predicateChoiceAvailable() ? "predicateChoice" : "ExactChoice")));
        sender.sendMessage(Text.msg("<gray>1 stamped token -> <white>diamond<gray> (crafting), or smelt for <white>emerald<gray>."));
        if (sender instanceof Player player) {
            give(player, stampedItem().asQuantity(8));
            give(player, lookAlikeItem().asQuantity(8));
            sender.sendMessage(Text.msg("<gray>You have 8 stamped and 8 unstamped look-alikes."));
        }
        sender.sendMessage(Text.msg("<gold>What to check on a Bedrock client:"));
        sender.sendMessage(Text.msg("<gray> 1. Craft with a <white>stamped<gray> token — should give a diamond."));
        sender.sendMessage(Text.msg("<gray> 2. Craft with an <white>unstamped<gray> one — a result may be SHOWN; taking it should fail."));
        sender.sendMessage(Text.msg("<gray> 3. Smelt a stamped token — should give an emerald, no ghost output."));
        sender.sendMessage(Text.msg("<gray> 4. Smelt an unstamped one — should just sit there."));
        sender.sendMessage(Text.msg("<gray> 5. Look for both in the Bedrock recipe book."));
        sender.sendMessage(Text.msg("<dark_gray>Turn off with /craftbridge spike off"));
    }

    private static RecipeChoice predicate(ItemStack example) {
        // Routed through the same helper the real custom items use, so the spike tests the
        // production path rather than a parallel one.
        return CustomItemChoice.forCustomItemPredicate(BedrockSpike::isStamped, example);
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item(name));
        meta.lore(Text.lore("Throwaway spike output."));
        item.setItemMeta(meta);
        return item;
    }

    private static void give(Player player, ItemStack stack) {
        for (ItemStack left : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    public void off(CommandSender sender, boolean quiet) {
        Bukkit.removeRecipe(CRAFT_KEY);
        Bukkit.removeRecipe(COOK_KEY);
        active = false;
        if (!quiet) {
            sender.sendMessage(Text.msg("<green>Spike recipes removed."));
        }
    }

    public boolean active() {
        return active;
    }
}
