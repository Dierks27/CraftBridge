package com.dierks.craftbridge.workbench;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.sort.SortCategoryRules;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Combo Chest GUI: a storage terminal for every chest, barrel, shulker box and
 * double chest within range (other terminals' barrels excluded), aggregated by item type.
 *
 * <ul>
 *   <li><b>Pull:</b> click an entry = one stack into your inventory; shift-click = as many as fit.</li>
 *   <li><b>Deposit:</b> click the GUI with an item on the cursor, shift-click from your
 *       inventory, or drag over it — routed to a container that already holds that type,
 *       else the nearest one with a free slot; refused (stays with you) if nothing has room.</li>
 *   <li><b>Filters:</b> all / blocks / tools &amp; armor / food / misc, from the sort categories.</li>
 *   <li><b>Live:</b> every click re-scans, so changes by hoppers or other players show on the next click.</li>
 * </ul>
 */
public final class ComboChestMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    enum Filter {
        ALL("All", Material.CHEST),
        BLOCKS("Blocks", Material.STONE),
        TOOLS_ARMOR("Tools & armor", Material.IRON_PICKAXE),
        FOOD("Food", Material.BREAD),
        MISC("Misc", Material.STRING);

        final String title;
        final Material icon;

        Filter(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    private final CraftBridgePlugin plugin;
    private final WorkbenchFeature feature;
    private final Player player;
    private final WorkbenchRecord record;
    private final SortCategoryRules categories;
    private int page;
    private Filter filter = Filter.ALL;
    private List<StorageScanner.Source> sources = List.of();

    public ComboChestMenu(CraftBridgePlugin plugin, WorkbenchFeature feature, Player player, WorkbenchRecord record) {
        this.plugin = plugin;
        this.feature = feature;
        this.player = player;
        this.record = record;
        this.categories = plugin.config().sortCategories();
        init(54, Text.item("<dark_aqua>Combo Chest"));
    }

    private record Entry(ItemStack key, int count) {
    }

    @Override
    protected boolean acceptsDeposits() {
        return true;
    }

    @Override
    protected ItemStack deposit(Player who, ItemStack stack) {
        rescan();
        ItemStack left = feature.scanner().deposit(sources, stack);
        int stored = stack.getAmount() - (Items.isEmpty(left) ? 0 : left.getAmount());
        if (stored <= 0) {
            who.sendMessage(Text.msg("<red>No room in nearby storage for " + Items.describe(stack) + "."));
        }
        refresh();
        return Items.isEmpty(left) ? null : left;
    }

    private void rescan() {
        // The scanner itself skips every Combo Chest barrel (this one included): terminals are never storage.
        sources = feature.scanner().scan(player, record.location(), plugin.config().radius(BlockKind.COMBO_CHEST));
    }

    private Filter filterOf(ItemStack key) {
        Material type = key.getType();
        String name = categories.nameOf(categories.categoryOf(type.name(), type.isBlock(), type.isEdible())).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "blocks" -> Filter.BLOCKS;
            case "tools", "weapons", "armor", "armour" -> Filter.TOOLS_ARMOR;
            case "food" -> Filter.FOOD;
            default -> Filter.MISC;
        };
    }

    @Override
    protected void build() {
        rescan();
        Map<ItemStack, Integer> totals = feature.scanner().aggregate(sources);
        List<Entry> entries = new ArrayList<>();
        totals.forEach((k, v) -> {
            if (filter == Filter.ALL || filterOf(k) == filter) {
                entries.add(new Entry(k, v));
            }
        });
        entries.sort(Comparator.<Entry>comparingInt(e -> categories.categoryOf(e.key().getType().name(),
                        e.key().getType().isBlock(), e.key().getType().isEdible()))
                .thenComparing(e -> e.key().getType().name())
                .thenComparing(e -> Items.describe(e.key())));

        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < entries.size(); i++) {
            Entry entry = entries.get(from + i);
            set(i, icon(entry), e -> {
                int wanted = e.isShiftClick() ? Integer.MAX_VALUE : entry.key().getMaxStackSize();
                int moved = feature.scanner().pullToPlayer(player, sources, entry.key(), wanted);
                if (moved == 0) {
                    player.sendMessage(Text.msg("<red>No room in your inventory."));
                }
                refresh();
            });
        }

        if (page > 0) {
            set(45, Items.icon(Material.ARROW, "<yellow>Previous page"), e -> {
                page--;
                refresh();
            });
        }
        int[] filterSlots = {46, 47, 48, 50, 51};
        Filter[] filters = Filter.values();
        for (int i = 0; i < filters.length; i++) {
            Filter f = filters[i];
            boolean selected = f == filter;
            ItemStack icon = Items.icon(f.icon, (selected ? "<green>" : "<white>") + f.title,
                    selected ? "<green>Showing" : "<yellow>Click <gray>to show only these");
            Items.glint(icon, selected);
            set(filterSlots[i], icon, e -> {
                filter = f;
                page = 0;
                refresh();
            });
        }
        set(49, Items.icon(Material.ENDER_CHEST, "<aqua>" + sources.size() + " container(s) within "
                        + plugin.config().radius(BlockKind.COMBO_CHEST) + " blocks",
                entries.size() + " item type(s) shown, page " + (page + 1) + "/" + pages,
                "",
                "<yellow>Click <gray>an item: take one stack",
                "<yellow>Shift-click<gray>: take as many as fit",
                "",
                "<yellow>Deposit<gray>: click here with an item,",
                "<gray>shift-click it in your inventory, or drag it in.",
                "<gray>It goes to a chest that already has that item,",
                "<gray>else the nearest one with space."), null);
        if (page < pages - 1) {
            set(53, Items.icon(Material.ARROW, "<yellow>Next page"), e -> {
                page++;
                refresh();
            });
        }
        fill(Icons.filler());
    }

    private static ItemStack icon(Entry entry) {
        ItemStack icon = entry.key().clone();
        icon.setAmount(Math.max(1, Math.min(entry.count(), icon.getMaxStackSize())));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
                lore.add(Component.empty());
            }
            lore.add(Text.item("<aqua>" + entry.count() + " <gray>in nearby storage"));
            lore.add(Text.item("<yellow>Click <gray>take a stack  <yellow>Shift <gray>take all that fit"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(Keys.GUI_BUTTON, PersistentDataType.BYTE, (byte) 1);
            icon.setItemMeta(meta);
        }
        return icon;
    }
}
