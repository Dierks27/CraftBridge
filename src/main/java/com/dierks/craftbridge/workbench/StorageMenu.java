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
import java.util.Map;

/**
 * Sneak + right-click on a Linked Workbench: everything in nearby storage, aggregated
 * by item with counts, 45 per page. Click pulls one stack into your inventory,
 * shift-click pulls as many as fit. This is the non-JEI / Bedrock way to use the table.
 */
public final class StorageMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final CraftBridgePlugin plugin;
    private final StorageScanner scanner;
    private final Player player;
    private final WorkbenchRecord record;
    private final SortCategoryRules categories;
    private int page;
    private List<StorageScanner.Source> sources = List.of();

    public StorageMenu(CraftBridgePlugin plugin, StorageScanner scanner, Player player, WorkbenchRecord record) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.player = player;
        this.record = record;
        this.categories = plugin.config().sortCategories();
        init(54, Text.item("<dark_aqua>Nearby storage"));
    }

    private record Entry(ItemStack key, int count) {
    }

    @Override
    protected void build() {
        sources = scanner.scan(player, record.location(), plugin.config().workbenchRadius());
        Map<ItemStack, Integer> totals = scanner.aggregate(sources);
        List<Entry> entries = new ArrayList<>();
        totals.forEach((k, v) -> entries.add(new Entry(k, v)));
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
                int moved = scanner.pullToPlayer(player, sources, entry.key(), wanted);
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
        set(49, Items.icon(Material.CHEST, "<aqua>" + sources.size() + " container(s) within "
                        + plugin.config().workbenchRadius() + " blocks",
                entries.size() + " different item(s), page " + (page + 1) + "/" + pages,
                "", "<yellow>Click <gray>an item: take one stack",
                "<yellow>Shift-click<gray>: take as many as fit",
                "", "Right-click the table (not sneaking)", "to open the crafting grid."), null);
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
