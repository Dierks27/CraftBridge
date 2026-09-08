package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.CraftBridgePlugin;
import com.dierks.craftbridge.util.Text;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/** Feature 4: admin-defined crafting recipes, GUI-driven via {@code /recipe}. */
public final class RecipeFeature implements CraftBridgePlugin.Feature, Listener {

    private final CraftBridgePlugin plugin;
    private final RecipeStore store;
    private final RecipeRegistry registry;

    public RecipeFeature(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.store = new RecipeStore(plugin);
        this.registry = new RecipeRegistry(plugin);
    }

    @Override
    public String name() {
        return "recipes";
    }

    @Override
    public void enable() {
        store.load();
        int count = registry.registerAll(store.all());
        plugin.getLogger().info("Registered " + count + " custom recipe(s) from recipes.yml"
                + (store.all().size() > count ? " (" + (store.all().size() - count) + " disabled/invalid)" : "") + ".");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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
