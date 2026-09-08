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
 * <p>Layout and colours are in {@link RecipeEditorLayout}: a cyan frame around the 3x3
 * grid, an orange one around the result, a muted one around the match-mode toggles, and a
 * neutral dark background everywhere else. The grid and result slots start genuinely empty
 * — no placeholder item is ever put in a slot the admin is meant to fill.
 *
 * <p>Items can be put in by hand, or picked from {@link ItemPickerMenu} by clicking an
 * empty slot with an empty hand — which needs neither the item nor creative mode.
 *
 * <p>Items in the grid are the admin's own and are returned on Save, Cancel or close.
 * When editing an existing recipe the grid is seeded with <em>display copies</em> tagged
 * {@code craftbridge:editor_seed}; those are discarded on close (and removed from the
 * admin's inventory if they were dragged out), so editing never duplicates items.
 */
public final class RecipeEditorMenu extends Menu {

    static final int[] GRID = RecipeEditorLayout.GRID;
    static final int[] INDICATOR = RecipeEditorLayout.INDICATOR;
    static final int RESULT = RecipeEditorLayout.RESULT;
    static final int ARROW = RecipeEditorLayout.ARROW;
    static final int TOGGLE_TYPE = RecipeEditorLayout.TOGGLE_TYPE;
    static final int SAVE = RecipeEditorLayout.SAVE;
    static final int CANCEL = RecipeEditorLayout.CANCEL;
    static final int TOGGLE_ENABLED = RecipeEditorLayout.TOGGLE_ENABLED;
    static final int INFO = RecipeEditorLayout.INFO;
    static final int WARNING = RecipeEditorLayout.WARNING;

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
    /** True while we deliberately swap the player over to the item picker and back. */
    private boolean switching;

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
        frame();
        for (int i = 0; i < 9; i++) {
            ItemStack cell = getInventory().getItem(GRID[i]);
            final int idx = i;
            if (Items.isEmpty(cell)) {
                set(INDICATOR[i], Items.icon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>empty slot",
                        "<gray>Put an item in the slot above,",
                        "<gray>or click it with an empty hand to", "<gray>pick one from the item list."), null);
                handler(GRID[i], e -> openPicker(GRID[idx], "grid slot " + (idx + 1)));
                continue;
            }
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
        set(ARROW, Items.icon(Material.SPECTRAL_ARROW, "<gray>Result →", "Put the crafted item (with its count)",
                "in the framed slot to the right, or click", "the empty slot to pick one from a list."), null);
        ItemStack result = getInventory().getItem(RESULT);
        if (Items.isEmpty(result)) {
            handler(RESULT, e -> openPicker(RESULT, "the result"));
        } else {
            int amount = result.getAmount();
            set(RecipeEditorLayout.COUNT_UP, Items.icon(Material.LIME_DYE, "<green>More <white>(" + amount + ")",
                    "<yellow>Click <gray>+1", "<yellow>Right-click <gray>+8"),
                    e -> changeCount(e.isRightClick() ? 8 : 1));
            set(RecipeEditorLayout.COUNT_DOWN, Items.icon(Material.RED_DYE, "<red>Fewer <white>(" + amount + ")",
                    "<yellow>Click <gray>-1", "<yellow>Right-click <gray>-8"),
                    e -> changeCount(e.isRightClick() ? -8 : -1));
        }
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
                "<aqua>Blue frame<gray>: the 3x3 crafting grid.",
                "<gold>Orange frame<gray>: the result.",
                "Put real items in, or click an empty slot",
                "with an empty hand to pick from a list -",
                "you do not need to own the item.",
                "Each filled grid slot gets a match toggle below.",
                "The result has +/- buttons for its count.",
                "Save checks whether the layout already crafts",
                "something; if so it asks you to click Save again."), null);
        if (conflictText != null) {
            set(WARNING, Items.icon(Material.YELLOW_STAINED_GLASS_PANE, "<gold>⚠ Layout already crafts something",
                    conflictText, "The older recipe may win at the table.",
                    "Change the layout, or click Save again."), null);
        }
        fill(Items.icon(Material.BLACK_STAINED_GLASS_PANE, " "));
    }

    /**
     * The frames that make the two regions readable: a cyan box down both sides of the
     * crafting grid, an orange one around the result with a labelled marker above it, and a
     * muted one around the match-mode block. Every one of these is a decorative slot with no
     * handler, so {@link com.dierks.craftbridge.gui.MenuListener} cancels any click on them —
     * and none of them is ever placed in a slot the admin fills.
     */
    private void frame() {
        for (int slot : RecipeEditorLayout.GRID_FRAME) {
            set(slot, Items.icon(Material.CYAN_STAINED_GLASS_PANE, "<aqua>Crafting grid",
                    "The 3x3 layout of the recipe."), null);
        }
        for (int slot : RecipeEditorLayout.RESULT_FRAME) {
            set(slot, Items.icon(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Result",
                    "What the recipe makes."), null);
        }
        for (int slot : RecipeEditorLayout.MATCH_FRAME) {
            set(slot, Items.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Match mode",
                    "One toggle per grid slot above."), null);
        }
        set(RecipeEditorLayout.RESULT_LABEL, Items.icon(Material.ITEM_FRAME, "<gold>▼ Result slot ▼",
                "The item this recipe makes.",
                "Put one in, or click the empty slot",
                "to pick from the item list."), null);
        set(RecipeEditorLayout.MATCH_LABEL, Items.icon(Material.COMPARATOR, "<gray>Match mode ▶",
                "For each filled grid slot above,",
                "whether any item of that type matches",
                "or only that exact item."), null);
    }

    /** Open the item picker for one slot, without ending the editing session. */
    private void openPicker(int slot, String what) {
        switching = true;
        org.bukkit.Bukkit.getScheduler().runTask(feature.plugin(), () -> {
            new ItemPickerMenu(feature, player, what, picked -> {
                getInventory().setItem(slot, tagSeed(picked));
                reopen();
            }, this::reopen, this::pickerClosed).openFor();
            switching = false;
        });
    }

    private void reopen() {
        org.bukkit.Bukkit.getScheduler().runTask(feature.plugin(), () -> {
            overrideArmed = false;
            conflictText = null;
            refresh();
            player.openInventory(getInventory());
        });
    }

    /**
     * The picker closed. If the player did not come back here (they pressed Escape), the
     * editing session is over and their items have to go back, exactly as closing the
     * editor itself would have done.
     */
    private void pickerClosed() {
        org.bukkit.Bukkit.getScheduler().runTaskLater(feature.plugin(), () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) != this) {
                finish();
            }
        }, 1L);
    }

    /**
     * Change the result count. A stack the admin physically put in is handed straight back
     * first and replaced with a display copy, so changing the count can never mint items.
     */
    private void changeCount(int delta) {
        ItemStack result = getInventory().getItem(RESULT);
        if (Items.isEmpty(result)) {
            return;
        }
        int max = Math.max(1, result.getMaxStackSize());
        int amount = Math.max(1, Math.min(max, result.getAmount() + delta));
        if (!isSeed(result)) {
            ItemStack back = result.clone();
            getInventory().setItem(RESULT, null);
            for (ItemStack left : player.getInventory().addItem(back).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
            player.sendMessage(Text.msg("<gray>Your " + Items.describe(back) + " went back to your inventory; "
                    + "the editor keeps a copy."));
        }
        ItemStack display = tagSeed(result.clone());
        display.setAmount(amount);
        getInventory().setItem(RESULT, display);
        overrideArmed = false;
        conflictText = null;
        refresh();
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
        if (switching) {
            return; // opening the item picker, not leaving the editor
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
