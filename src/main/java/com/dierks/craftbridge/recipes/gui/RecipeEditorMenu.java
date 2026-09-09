package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.recipes.CustomRecipe;
import com.dierks.craftbridge.recipes.Ingredient;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.recipes.RecipeShape;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Two kinds of thing can sit in an input slot, and the difference is what keeps the
 * editor from minting items:
 * <ul>
 *   <li><b>Real items</b> the admin physically placed. The slot stays editable, vanilla
 *       click behaviour applies, and every one of them is handed back on Save, Cancel or
 *       close — exactly once.</li>
 *   <li><b>Ghosts</b> chosen from {@link ItemPickerMenu} (and the copies used to seed an
 *       existing recipe for editing). These live in the {@code ghosts} map, never in the
 *       player's reach: the slot is switched to non-editable while a ghost is in it, so
 *       every click, shift-click, drag, number-key swap and drop is cancelled. The only
 *       things a ghost slot accepts are "replace it" (click) and "clear it"
 *       (right-click), and on close a ghost simply stops existing.</li>
 * </ul>
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

    private final RecipeFeature feature;
    private final Player player;
    private final String existingId;
    private boolean shaped = true;
    private boolean enabled = true;
    private final boolean[] exact = new boolean[9];
    /**
     * Ingredients chosen from the item picker, by slot. These are <em>ghosts</em>: the GUI
     * shows them so the recipe can be read and edited, but they are menu state, never
     * inventory contents the player can take. A slot holding one is switched to
     * non-editable, so every click, shift-click, drag, number-key swap and drop on it is
     * cancelled. Items the admin physically placed are not in here — those are real, stay
     * in the inventory slot, and are handed back on close.
     */
    private final Map<Integer, ItemStack> ghosts = new HashMap<>();
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
                ghosts.put(GRID[i], ing.display());
            }
        }
        ghosts.put(RESULT, recipe.result().clone());
    }

    /** What is in a slot for recipe purposes: the ghost if there is one, else the real item. */
    private ItemStack contentOf(int slot) {
        ItemStack ghost = ghosts.get(slot);
        if (ghost != null) {
            return ghost;
        }
        ItemStack real = getInventory().getItem(slot);
        return Items.isEmpty(real) ? null : real;
    }

    /** Draw one input slot: a ghost (locked), a real item (vanilla), or empty (opens the picker). */
    private void drawInput(int slot, String what) {
        ItemStack ghost = ghosts.get(slot);
        if (ghost != null) {
            editable(slot, false);
            set(slot, ghostDisplay(ghost), e -> {
                if (e.isRightClick()) {
                    ghosts.remove(slot);
                    onInputChanged();
                } else {
                    openPicker(slot, what);
                }
            });
            return;
        }
        editable(slot, true);
        if (Items.isEmpty(getInventory().getItem(slot))) {
            // Only reachable with an empty cursor on an empty slot, where vanilla does nothing.
            handler(slot, e -> openPicker(slot, what));
        }
    }

    /** The ghost as the player sees it: the item, plus a line saying it cannot be taken. */
    private static ItemStack ghostDisplay(ItemStack ghost) {
        ItemStack copy = ghost.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(Text.item("<dark_gray>Picked from the item list"));
            lore.add(Text.item("<yellow>Click <gray>to change  <yellow>Right-click <gray>to clear"));
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private void onInputChanged() {
        overrideArmed = false;
        conflictText = null;
        refresh();
    }

    @Override
    protected void build() {
        frame();
        for (int i = 0; i < 9; i++) {
            drawInput(GRID[i], "grid slot " + (i + 1));
            ItemStack cell = contentOf(GRID[i]);
            final int idx = i;
            if (Items.isEmpty(cell)) {
                set(INDICATOR[i], Items.icon(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>empty slot",
                        "<gray>Put an item in the slot above,",
                        "<gray>or click it with an empty hand to", "<gray>pick one from the item list."), null);
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
        drawInput(RESULT, "the result");
        ItemStack result = contentOf(RESULT);
        if (!Items.isEmpty(result)) {
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
                giveBackReal(slot);
                ghosts.put(slot, picked);
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
     * first and becomes a ghost, so changing the count can never mint items.
     */
    private void changeCount(int delta) {
        ItemStack result = contentOf(RESULT);
        if (Items.isEmpty(result)) {
            return;
        }
        int max = Math.max(1, result.getMaxStackSize());
        int amount = Math.max(1, Math.min(max, result.getAmount() + delta));
        ItemStack ghost = result.clone();
        if (!ghosts.containsKey(RESULT)) {
            player.sendMessage(Text.msg("<gray>Your " + Items.describe(result)
                    + " went back to your inventory; the editor keeps a copy."));
            giveBackReal(RESULT);
        }
        ghost.setAmount(amount);
        ghosts.put(RESULT, ghost);
        onInputChanged();
    }

    /** Hand back whatever real item is in a slot (a no-op for an empty or ghost slot). */
    private void giveBackReal(int slot) {
        if (ghosts.containsKey(slot)) {
            return;
        }
        ItemStack real = getInventory().getItem(slot);
        getInventory().setItem(slot, null);
        if (Items.isEmpty(real)) {
            return;
        }
        for (ItemStack left : player.getInventory().addItem(real).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
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
        ItemStack result = contentOf(RESULT);
        return Items.isEmpty(result) ? "?" : feature.store().freeId(result.getType());
    }

    private void save() {
        ItemStack result = contentOf(RESULT);
        if (Items.isEmpty(result)) {
            player.sendMessage(Text.msg("<red>Put the result item in the slot next to the arrow first."));
            return;
        }
        List<Ingredient> grid = new ArrayList<>(9);
        boolean any = false;
        for (int i = 0; i < 9; i++) {
            ItemStack cell = contentOf(GRID[i]);
            if (Items.isEmpty(cell)) {
                grid.add(null);
            } else {
                any = true;
                grid.add(exact[i] ? Ingredient.ofExact(cell.clone()) : Ingredient.ofMaterial(cell.getType()));
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
            recipe = new CustomRecipe(id, true, enabled, groupOf(id), result.clone(), shape.rows(), shape.legend(), List.of());
        } else {
            List<Ingredient> ingredients = new ArrayList<>();
            for (Ingredient ing : grid) {
                if (ing != null) {
                    ingredients.add(ing);
                }
            }
            recipe = new CustomRecipe(id, false, enabled, groupOf(id), result.clone(), List.of(), java.util.Map.of(), ingredients);
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

    /**
     * Give the admin back every item they physically placed. Ghosts are menu state, never
     * inventory contents, so there is nothing to clean up for them — which is exactly why
     * a picked item can no longer be taken out of the GUI.
     */
    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        int[] slots = new int[GRID.length + 1];
        System.arraycopy(GRID, 0, slots, 0, GRID.length);
        slots[GRID.length] = RESULT;
        for (int slot : slots) {
            if (ghosts.containsKey(slot)) {
                getInventory().setItem(slot, null);
                continue;
            }
            giveBackReal(slot);
        }
        ghosts.clear();
    }

    @Override
    protected void onClose(InventoryCloseEvent event) {
        if (switching) {
            return; // opening the item picker, not leaving the editor
        }
        finish();
    }

}
