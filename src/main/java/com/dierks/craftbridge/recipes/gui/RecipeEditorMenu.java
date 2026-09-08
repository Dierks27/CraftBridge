package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.Ingredient;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.recipes.RecipeShape;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Keys;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe editor: a real 3x3 grid + result slot the admin fills with real items.
 *
 * <pre>
 *  row 0:  . [g][g][g] .  .  .  .  [type]
 *  row 1:  . [g][g][g] . [→][R] .  [save]
 *  row 2:  . [g][g][g] .  .  .  .  [cancel]
 *  row 3:  . [m][m][m] .  .  .  .  [enabled]
 *  row 4:  . [m][m][m] .  .  .  .  [info]
 *  row 5:  . [m][m][m] .  .  .  .  [warning]
 * </pre>
 * {@code g} = editable grid slot, {@code m} = the match-mode indicator for the grid slot
 * three rows above it (click to toggle "any item of this material" vs "this exact item").
 *
 * <p>Items in the grid are the admin's own and are returned on Save, Cancel or close.
 * When editing an existing recipe the grid is seeded with <em>display copies</em> tagged
 * {@code craftbridge:editor_seed}; those are discarded on close (and removed from the
 * admin's inventory if they were dragged out), so editing never duplicates items.
 */
public final class RecipeEditorMenu extends Menu {

    static final int[] GRID = {1, 2, 3, 10, 11, 12, 19, 20, 21};
    static final int[] INDICATOR = {28, 29, 30, 37, 38, 39, 46, 47, 48};
    static final int RESULT = 15;
    static final int ARROW = 14;
    static final int TOGGLE_TYPE = 8;
    static final int SAVE = 17;
    static final int CANCEL = 26;
    static final int TOGGLE_ENABLED = 35;
    static final int INFO = 44;
    static final int WARNING = 53;

    private static final NamespacedKey SEED = Keys.key("editor_seed");

    private final RecipeFeature feature;
    private final Player player;
    private final String existingId;
    private boolean shaped = true;
    private boolean enabled = true;
    private final boolean[] exact = new boolean[9];
    private boolean overrideArmed;
    private String conflictText;
    private boolean finished;

    public RecipeEditorMenu(RecipeFeature feature, Player player, String existingId) {
        this.feature = feature;
        this.player = player;
        this.existingId = existingId;
        init(54, Text.item(existingId == null ? "<dark_green>New recipe" : "<dark_green>Edit: <gray>" + existingId));
        editable(GRID);
        editable(RESULT);
        CustomRecipe existing = existingId == null ? null : feature.store().get(existingId);
        if (existing != null) {
            seed(existing);
        }
    }

    private void seed(CustomRecipe recipe) {
        shaped = recipe.shaped();
        enabled = recipe.enabled();
        List<Ingredient> grid = recipe.asGrid();
        for (int i = 0; i < 9; i++) {
            Ingredient ing = grid.get(i);
            if (ing != null) {
                exact[i] = ing.isExact();
                getInventory().setItem(GRID[i], tagSeed(ing.display()));
            }
        }
        getInventory().setItem(RESULT, tagSeed(recipe.result()));
    }

    @Override
    protected void build() {
        for (int i = 0; i < 9; i++) {
            ItemStack cell = getInventory().getItem(GRID[i]);
            if (Items.isEmpty(cell)) {
                set(INDICATOR[i], Items.icon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>empty slot"), null);
                continue;
            }
            final int idx = i;
            String matName = Items.prettyMaterial(cell.getType());
            ItemStack icon = exact[i]
                    ? Items.icon(Material.NAME_TAG, "<light_purple>Match: this exact item",
                    "Only an item identical to the one above", "(name, enchantments, components) matches.",
                    "", "<yellow>Click <gray>to match any " + matName)
                    : Items.icon(Material.PAPER, "<white>Match: any " + matName,
                    "Any " + matName + " works in this slot,", "whatever its name or enchantments.",
                    "", "<yellow>Click <gray>to require this exact item");
            set(INDICATOR[i], icon, e -> {
                exact[idx] = !exact[idx];
                overrideArmed = false;
                refresh();
            });
        }
        set(ARROW, Items.icon(Material.SPECTRAL_ARROW, "<gray>Result →", "Put the crafted item (with its count)", "in the slot to the right."), null);
        set(TOGGLE_TYPE, Items.icon(shaped ? Material.CRAFTING_TABLE : Material.CAULDRON,
                shaped ? "<white>Type: <aqua>Shaped" : "<white>Type: <gold>Shapeless",
                shaped ? "Positions matter (empty rows/columns" : "Any arrangement of these items works.",
                shaped ? "around the items are trimmed)." : "",
                "", "<yellow>Click <gray>to switch"), e -> {
            shaped = !shaped;
            overrideArmed = false;
            refresh();
        });
        List<String> saveLore = new ArrayList<>();
        saveLore.add("Id: <white>" + previewId());
        if (conflictText != null) {
            saveLore.add("");
            saveLore.add("<gold>Overlaps: <white>" + conflictText);
            saveLore.add("<gold>Click again to save anyway.");
        }
        set(SAVE, Items.icon(conflictText != null ? Material.ORANGE_CONCRETE : Material.LIME_CONCRETE,
                conflictText != null ? "<gold>Save anyway" : "<green>Save", Text.lore(saveLore)), e -> save());
        set(CANCEL, Items.icon(Material.RED_CONCRETE, "<red>Cancel", "Your items are returned."), e -> {
            finish();
            new RecipeMainMenu(feature, player).open(player);
        });
        if (existingId != null) {
            set(TOGGLE_ENABLED, Icons.toggle(enabled, "<white>Recipe enabled",
                    "Disabled recipes stay in recipes.yml", "but cannot be crafted."), e -> {
                enabled = !enabled;
                refresh();
            });
        }
        set(INFO, Items.icon(Material.BOOK, "<aqua>How this works",
                "Put real items in the 3x3 grid on the left",
                "and the result in the slot next to the arrow.",
                "Each filled slot gets a match toggle below it.",
                "The id is made from the result item.",
                "Save checks whether the layout already crafts",
                "something; if so it asks you to click Save again."), null);
        if (conflictText != null) {
            set(WARNING, Items.icon(Material.YELLOW_STAINED_GLASS_PANE, "<gold>⚠ Layout already crafts something",
                    conflictText, "The older recipe may win at the table.",
                    "Change the layout, or click Save again."), null);
        }
        fill(Icons.filler());
    }

    @Override
    protected void onEditableSlotChanged(Player who, int rawSlot) {
        overrideArmed = false;
        conflictText = null;
        refresh();
    }

    private String previewId() {
        if (existingId != null) {
            return existingId;
        }
        ItemStack result = getInventory().getItem(RESULT);
        return Items.isEmpty(result) ? "?" : feature.store().freeId(result.getType());
    }

    private void save() {
        ItemStack result = getInventory().getItem(RESULT);
        if (Items.isEmpty(result)) {
            player.sendMessage(Text.msg("<red>Put the result item in the slot next to the arrow first."));
            return;
        }
        List<Ingredient> grid = new ArrayList<>(9);
        boolean any = false;
        for (int i = 0; i < 9; i++) {
            ItemStack cell = getInventory().getItem(GRID[i]);
            if (Items.isEmpty(cell)) {
                grid.add(null);
            } else {
                any = true;
                grid.add(exact[i] ? Ingredient.ofExact(clean(cell)) : Ingredient.ofMaterial(cell.getType()));
            }
        }
        if (!any) {
            player.sendMessage(Text.msg("<red>The crafting grid is empty."));
            return;
        }
        String id = existingId != null ? existingId : feature.store().freeId(result.getType());
        Recipe conflict = feature.registry().conflictFor(grid, player.getWorld(), id);
        if (conflict != null && !overrideArmed) {
            overrideArmed = true;
            conflictText = describe(conflict);
            refresh();
            player.sendMessage(Text.msg("<gold>That layout already crafts <white>" + conflictText
                    + "<gold>. Click Save again to add the recipe anyway."));
            return;
        }
        CustomRecipe recipe;
        if (shaped) {
            RecipeShape.Shape<Ingredient> shape = RecipeShape.of(grid, Ingredient::sameAs);
            recipe = new CustomRecipe(id, true, enabled, groupOf(id), clean(result), shape.rows(), shape.legend(), List.of());
        } else {
            List<Ingredient> ingredients = new ArrayList<>();
            for (Ingredient ing : grid) {
                if (ing != null) {
                    ingredients.add(ing);
                }
            }
            recipe = new CustomRecipe(id, false, enabled, groupOf(id), clean(result), List.of(), java.util.Map.of(), ingredients);
        }
        boolean ok = feature.save(recipe);
        finish();
        player.sendMessage(Text.msg(ok
                ? "<green>Saved recipe <white>" + id + "<green>: " + result.getAmount() + "x " + Items.describe(result) + "."
                : "<red>Saved to recipes.yml but the server rejected the recipe; check the console."));
        new RecipeBrowseMenu(feature, player, 0).open(player);
    }

    private String groupOf(String id) {
        CustomRecipe existing = existingId == null ? null : feature.store().get(existingId);
        return existing == null ? "" : existing.group();
    }

    private static String describe(Recipe recipe) {
        String what = Items.describe(recipe.getResult());
        if (recipe instanceof Keyed keyed) {
            return what + " (" + keyed.getKey().asString() + ")";
        }
        return what;
    }

    /** Give back the admin's real items and drop the seeded display copies. */
    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        int[] slots = new int[GRID.length + 1];
        System.arraycopy(GRID, 0, slots, 0, GRID.length);
        slots[GRID.length] = RESULT;
        for (int slot : slots) {
            ItemStack stack = getInventory().getItem(slot);
            getInventory().setItem(slot, null);
            if (Items.isEmpty(stack) || isSeed(stack)) {
                continue;
            }
            for (ItemStack left : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        // Seed copies that were dragged out must not survive as real items.
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isSeed(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isSeed(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    @Override
    protected void onClose(InventoryCloseEvent event) {
        if (isSeed(event.getView().getCursor())) {
            event.getView().setCursor(null);
        }
        finish();
    }

    private static ItemStack tagSeed(ItemStack stack) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(SEED, PersistentDataType.BYTE, (byte) 1);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private static boolean isSeed(ItemStack stack) {
        if (Items.isEmpty(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(SEED, PersistentDataType.BYTE);
    }

    /** A copy without the seed tag, so stored recipes never carry editor bookkeeping. */
    private static ItemStack clean(ItemStack stack) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(SEED, PersistentDataType.BYTE)) {
            meta.getPersistentDataContainer().remove(SEED);
            copy.setItemMeta(meta);
        }
        return copy;
    }
}
