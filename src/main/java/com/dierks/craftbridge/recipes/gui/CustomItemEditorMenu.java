package com.dierks.craftbridge.recipes.gui;

import com.dierks.craftbridge.gui.Icons;
import com.dierks.craftbridge.gui.Menu;
import com.dierks.craftbridge.items.CustomItemDef;
import com.dierks.craftbridge.items.CustomItemIds;
import com.dierks.craftbridge.recipes.RecipeFeature;
import com.dierks.craftbridge.util.Items;
import com.dierks.craftbridge.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Create or edit one custom item definition.
 *
 * <p>Laid out like {@link RecipeEditorMenu}: a live preview where the editor's result slot
 * sits, the editable fields down the middle, and Save / Cancel in the same right-hand column,
 * so an admin who knows one screen knows this one.
 *
 * <pre>
 *  row 0:  .  .  [B]  .  .  [f][P][f]  .
 *  row 1:  .  .  .  .  .  .  .  .  [save]
 *  row 2:  .  .  [N]  .  .  [f][f][f] [cancel]
 *  row 3:  .  .  .  .  .  .  .  .  [info]
 *  row 4:  .  .  [L]  .  .  .  .  .  .
 *  (row 4 also holds [T] when the base is a player head)
 * </pre>
 * {@code B} = base material, {@code N} = display name, {@code L} = lore, {@code T} = head
 * texture, {@code P} = the live preview, {@code f} = the orange preview frame.
 * Every slot here is a button or decoration — none is editable, so nothing can be taken out.
 *
 * <p>Name and lore are typed in chat rather than an anvil field: the plugin is deliberately
 * chest-GUI-only so Bedrock players get identical screens, and chat is the one text input
 * every client has. See {@link com.dierks.craftbridge.gui.ChatPrompt}.
 */
public final class CustomItemEditorMenu extends Menu {

    static final int BASE = 11;
    static final int NAME = 20;
    static final int LORE = 29;
    static final int TEXTURE = 38;
    static final int PREVIEW = 15;
    static final int SAVE = 17;
    static final int CANCEL = 26;
    static final int INFO = 35;
    /** The framed box around the preview, mirroring the recipe editor's result frame. */
    static final int[] PREVIEW_FRAME = {5, 7, 14, 16, 23, 24, 25};

    @Override
    protected String requiredPermission() {
        return RecipeFeature.ADMIN_PERMISSION;
    }

    private final RecipeFeature feature;
    private final Player player;
    private final String existingId;

    private String id;
    private Material base = Material.PAPER;
    private String name = "";
    private final List<String> lore = new ArrayList<>();
    private String headTexture;

    public CustomItemEditorMenu(RecipeFeature feature, Player player, String existingId) {
        this.feature = feature;
        this.player = player;
        this.existingId = existingId;
        init(45, Text.item(existingId == null ? "<dark_green>New custom item"
                : "<dark_green>Edit item: <gray>" + existingId));
        CustomItemDef existing = existingId == null ? null : feature.customItems().get(existingId);
        if (existing != null) {
            id = existing.id();
            base = existing.base();
            name = existing.name();
            lore.addAll(existing.lore());
            headTexture = existing.headTexture();
        }
    }

    /** The definition as it currently stands, or null while it is not yet valid. */
    private CustomItemDef current() {
        String effectiveId = effectiveId();
        if (!CustomItemIds.isValid(effectiveId)) {
            return null;
        }
        try {
            return new CustomItemDef(effectiveId, base, name.isBlank() ? effectiveId : name, lore, headTexture);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Editing keeps the existing id; a new item derives one from the name the admin typed. */
    private String effectiveId() {
        if (existingId != null) {
            return existingId;
        }
        if (id != null && !id.isBlank()) {
            return id;
        }
        String fromName = CustomItemIds.sanitise(stripTags(name));
        return fromName.isEmpty() ? "" : feature.customItems().freeId(fromName);
    }

    @Override
    protected void build() {
        for (int slot : PREVIEW_FRAME) {
            set(slot, Items.icon(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Preview",
                    "What players will see."), null);
        }
        set(BASE, Items.icon(base, "<white>Base item: <aqua>" + Items.prettyMaterial(base),
                "The vanilla item this is built on.",
                "",
                "<yellow>Click <gray>to pick a different one"), e -> openBasePicker());
        set(NAME, Items.icon(Material.NAME_TAG, "<white>Display name",
                name.isBlank() ? "<dark_gray>not set" : "<gray>" + name,
                "",
                "MiniMessage, e.g. <dark_gray>&lt;dark_gray&gt;Burned Zombie Flesh",
                "<yellow>Click <gray>to type a new one"), e -> promptName());
        List<String> loreLines = new ArrayList<>();
        if (lore.isEmpty()) {
            loreLines.add("<dark_gray>no lore");
        } else {
            for (String line : lore) {
                loreLines.add("<gray>" + line);
            }
        }
        loreLines.add("");
        loreLines.add("<yellow>Click <gray>to add a line");
        loreLines.add("<yellow>Right-click <gray>to remove the last");
        set(LORE, Items.icon(Material.WRITABLE_BOOK, "<white>Lore <dark_gray>(" + lore.size() + ")",
                Text.lore(loreLines)), e -> {
            if (e.isRightClick()) {
                if (!lore.isEmpty()) {
                    lore.remove(lore.size() - 1);
                    refresh();
                }
                return;
            }
            promptLore();
        });
        if (base == Material.PLAYER_HEAD) {
            set(TEXTURE, Items.icon(Material.PLAYER_HEAD, "<white>Head texture",
                    headTexture == null ? "<dark_gray>none (a default head)" : "<gray>set",
                    "The base64 'textures' value from a",
                    "head site. Heads cannot be placed or worn.",
                    "",
                    "<yellow>Click <gray>to paste one",
                    "<yellow>Right-click <gray>to clear"), e -> {
                if (e.isRightClick()) {
                    headTexture = null;
                    refresh();
                    return;
                }
                promptTexture();
            });
        }
        CustomItemDef preview = current();
        if (preview != null) {
            set(PREVIEW, feature.customItems().create(preview, 1), null);
        } else {
            set(PREVIEW, Items.icon(Material.BARRIER, "<red>Not ready",
                    "Give the item a display name first."), null);
        }
        String targetId = effectiveId();
        List<String> saveLore = new ArrayList<>();
        saveLore.add("Id: <white>" + (targetId.isEmpty() ? "?" : targetId));
        if (existingId == null && !targetId.isEmpty()) {
            saveLore.add("<dark_gray>derived from the display name");
        }
        set(SAVE, Items.icon(preview == null ? Material.GRAY_CONCRETE : Material.LIME_CONCRETE,
                preview == null ? "<gray>Save" : "<green>Save", Text.lore(saveLore)), e -> save());
        set(CANCEL, Items.icon(Material.RED_CONCRETE, "<red>Cancel", "Nothing is written."),
                e -> new CustomItemBrowseMenu(feature, player, 0).open(player));
        set(INFO, Items.icon(Material.BOOK, "<aqua>How this works",
                "The id is stamped into the item as a hidden",
                "tag. Recipes match that tag, so renaming a",
                "vanilla item on an anvil cannot forge one.",
                "",
                "Custom items do not stack with their plain",
                "counterparts. That is intended - it is what",
                "keeps them tellable apart in a chest.",
                "",
                "Editing a definition updates every recipe",
                "that references it."), null);
        fill(Icons.filler());
    }

    /**
     * Open the item picker on the next tick. Opening an inventory from inside an
     * {@code InventoryClickEvent} handler is not safe — the click is still being processed —
     * which is why {@link RecipeEditorMenu#openPicker} schedules it too.
     */
    private void openBasePicker() {
        org.bukkit.Bukkit.getScheduler().runTask(feature.plugin(), () ->
                new ItemPickerMenu(feature, player, "the base item",
                        picked -> {
                            base = picked.getType();
                            reopen();
                        },
                        this::reopen,
                        null).openFor());
    }

    private void reopen() {
        org.bukkit.Bukkit.getScheduler().runTask(feature.plugin(), () -> {
            refresh();
            player.openInventory(getInventory());
        });
    }

    private void promptName() {
        feature.chatPrompt().ask(feature.plugin(), player,
                "<gray>Type the item's display name (MiniMessage, e.g. <white>&lt;dark_gray&gt;Burned Zombie Flesh<gray>).",
                text -> {
                    name = text;
                    reopen();
                },
                this::reopen);
    }

    private void promptLore() {
        feature.chatPrompt().ask(feature.plugin(), player,
                "<gray>Type a lore line to add (MiniMessage).",
                text -> {
                    lore.add(text);
                    reopen();
                },
                this::reopen);
    }

    private void promptTexture() {
        feature.chatPrompt().ask(feature.plugin(), player,
                "<gray>Paste the base64 <white>textures<gray> value for the head.",
                text -> {
                    headTexture = text;
                    reopen();
                },
                this::reopen);
    }

    private void save() {
        CustomItemDef def = current();
        if (def == null) {
            player.sendMessage(Text.msg("<red>Give the item a display name first."));
            return;
        }
        boolean isNew = existingId == null;
        if (isNew && feature.customItems().contains(def.id())) {
            player.sendMessage(Text.msg("<red>A custom item called <white>" + def.id() + "<red> already exists."));
            return;
        }
        feature.saveItem(def);
        player.sendMessage(Text.msg("<green>Saved custom item <white>" + def.id() + "<green>."));
        new CustomItemBrowseMenu(feature, player, 0).open(player);
    }

    /** Strip MiniMessage tags so an id derived from a coloured name is still readable. */
    private static String stripTags(String miniMessage) {
        return miniMessage == null ? "" : miniMessage.replaceAll("<[^>]*>", "");
    }

}
