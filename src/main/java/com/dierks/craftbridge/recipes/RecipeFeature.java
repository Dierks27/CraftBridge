package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.items.CustomItemChoice;
import com.dierks.craftbridge.items.CustomItemDef;
import com.dierks.craftbridge.items.CustomItemListener;
import com.dierks.craftbridge.items.CustomItemRegistry;
import com.dierks.craftbridge.items.CustomItemStore;
import com.dierks.craftbridge.gui.ChatPrompt;
import com.dierks.craftbridge.util.Text;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;

/** Feature 4: admin-defined crafting recipes, GUI-driven via {@code /recipe}. */
public final class RecipeFeature implements CraftBridgePlugin.Feature, Listener {

    /** Gates /recipe and every screen it opens. Re-checked on each click, not just on open. */
    public static final String ADMIN_PERMISSION = "craftbridge.recipes.admin";

    private final CraftBridgePlugin plugin;
    private final CustomItemRegistry customItems = new CustomItemRegistry();
    private final CustomItemStore itemStore;
    private final RecipeStore store;
    private final RecipeRegistry registry;
    private final CustomItemListener itemListener = new CustomItemListener(customItems);
    private ChatPrompt chatPrompt;

    public RecipeFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.itemStore = new CustomItemStore(plugin);
        this.store = new RecipeStore(plugin, customItems);
        this.registry = new RecipeRegistry(plugin, customItems);
    }

    @Override
    public String name() {
        return "recipes";
    }

    @Override
    public void enable() {
        // Custom items first: recipes reference them by id and resolve them at registration.
        itemStore.load();
        customItems.replaceAll(itemStore.all());
        if (!customItems.isEmpty()) {
            plugin.getLogger().info("Loaded " + customItems.all().size() + " custom item(s); "
                    + CustomItemChoice.describeMode() + ".");
        }
        store.load();
        int count = registry.registerAll(store.all());
        plugin.getLogger().info("Registered " + count + " custom recipe(s) from recipes.yml"
                + (store.all().size() > count ? " (" + (store.all().size() - count) + " disabled/invalid)" : "") + ".");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(itemListener, plugin);
        this.chatPrompt = new ChatPrompt(plugin);
        warnAboutOrphanedCustomItems();
        PluginCommand cmd = plugin.getCommand("recipe");
        if (cmd != null) {
            RecipeCommand executor = new RecipeCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    @Override
    public void disable() {
        registry.unregisterAll();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(itemListener);
        if (chatPrompt != null) {
            chatPrompt.shutdown();
            chatPrompt = null;
        }
        PluginCommand cmd = plugin.getCommand("recipe");
        if (cmd != null) {
            cmd.setExecutor((sender, c, l, a) -> {
                sender.sendMessage(Text.msg("<red>Custom recipes are disabled in config.yml."));
                return true;
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        registry.unlockFor(event.getPlayer());
    }

    public CraftBridgePlugin plugin() {
        return plugin;
    }

    public RecipeStore store() {
        return store;
    }

    public CustomItemRegistry customItems() {
        return customItems;
    }

    /** The "type it in chat" helper the custom-item editor uses for names and lore. */
    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    public CustomItemStore itemStore() {
        return itemStore;
    }

    /** Add or replace a custom item, then re-register every recipe that references it. */
    public void saveItem(CustomItemDef def) {
        itemStore.put(def);
        customItems.put(def);
        // Recipes hold the id, not a copy, so re-registering picks up the new name/lore/base.
        registry.registerAll(store.all());
        plugin.getLogger().info("Custom item '" + def.id() + "' saved.");
    }

    /**
     * Delete a custom item. Recipes that referenced it are left in place but will fail to
     * register with a logged "unknown custom item" — deleting their input silently would be
     * worse, because the recipe would quietly start matching nothing.
     */
    public List<String> deleteItem(String id) {
        List<String> affected = new ArrayList<>();
        for (CustomRecipe recipe : store.all().values()) {
            if (recipe.customItemIds().contains(id)) {
                affected.add(recipe.id());
            }
        }
        itemStore.remove(id);
        customItems.remove(id);
        registry.registerAll(store.all());
        plugin.getLogger().info("Custom item '" + id + "' deleted"
                + (affected.isEmpty() ? "." : "; recipes now broken: " + String.join(", ", affected)));
        return affected;
    }

    /** Log recipes pointing at custom items that do not exist, so the cause is findable. */
    private void warnAboutOrphanedCustomItems() {
        for (CustomRecipe recipe : store.all().values()) {
            for (String id : recipe.customItemIds()) {
                if (!customItems.contains(id)) {
                    plugin.getLogger().warning("Recipe '" + recipe.id() + "' references custom item '"
                            + id + "', which is not defined in custom-items.yml.");
                }
            }
        }
    }

    public RecipeRegistry registry() {
        return registry;
    }

    /** Add or replace a recipe: persisted, registered, clients notified. */
    public boolean save(CustomRecipe recipe) {
        store.put(recipe);
        boolean ok = registry.register(recipe, true);
        plugin.getLogger().info("Recipe '" + recipe.id() + "' saved" + (ok ? " and registered." : " (not registered)."));
        return ok;
    }

    public void delete(String id) {
        if (store.remove(id) != null) {
            registry.unregister(id, true);
            plugin.getLogger().info("Recipe '" + id + "' deleted.");
        }
    }

    public void setEnabled(String id, boolean enabled) {
        CustomRecipe recipe = store.get(id);
        if (recipe == null || recipe.enabled() == enabled) {
            return;
        }
        CustomRecipe updated = recipe.withEnabled(enabled);
        store.put(updated);
        registry.register(updated, true);
    }

    /** Re-read recipes.yml and re-register everything. Returns how many are active. */
    public int reload() {
        store.load();
        return registry.registerAll(store.all());
    }

    /** Import the bundled starter pack; returns the ids added. */
    public List<String> importStarter() {
        List<String> added = store.importStarter();
        if (!added.isEmpty()) {
            registry.registerAll(store.all());
        }
        return added;
    }
}
