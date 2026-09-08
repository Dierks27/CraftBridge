package com.dierks.craftbridge;

import com.dierks.craftbridge.command.CraftBridgeCommand;
import com.dierks.craftbridge.config.CraftBridgeConfig;
import com.dierks.craftbridge.gui.MenuListener;
import com.dierks.craftbridge.integration.ContainerAccess;
import com.dierks.craftbridge.sort.SortFeature;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * CraftBridge entry point. Each feature is an independent {@link Feature} that is only
 * constructed when its master switch in {@code config.yml} is on, so a feature that
 * misbehaves on the live server can be turned off without touching the others.
 */
public final class CraftBridgePlugin extends JavaPlugin {

    private CraftBridgeConfig config;
    private ContainerAccess containerAccess;
    private final List<Feature> features = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new CraftBridgeConfig(this);
        this.containerAccess = new ContainerAccess(this);

        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        PluginCommand root = getCommand("craftbridge");
        if (root != null) {
            CraftBridgeCommand executor = new CraftBridgeCommand(this);
            root.setExecutor(executor);
            root.setTabCompleter(executor);
        }

        enableFeatures();
        getLogger().info("CraftBridge " + getPluginMeta().getVersion() + " enabled with: " + enabledFeatureNames());
    }

    @Override
    public void onDisable() {
        disableFeatures();
    }

    private void enableFeatures() {
        if (config.sortingEnabled()) {
            features.add(new SortFeature(this));
        }
        for (Feature feature : features) {
            try {
                feature.enable();
            } catch (RuntimeException ex) {
                getLogger().severe("Feature " + feature.name() + " failed to enable: " + ex);
                ex.printStackTrace();
            }
        }
    }

    private void disableFeatures() {
        for (Feature feature : features) {
            try {
                feature.disable();
            } catch (RuntimeException ex) {
                getLogger().warning("Feature " + feature.name() + " failed to disable cleanly: " + ex);
            }
        }
        features.clear();
        HandlerList.unregisterAll(this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
    }

    /** {@code /craftbridge reload}: re-read config.yml and rebuild every feature. */
    public void reloadEverything() {
        disableFeatures();
        reloadConfig();
        this.config = new CraftBridgeConfig(this);
        this.containerAccess = new ContainerAccess(this);
        enableFeatures();
        getLogger().info("CraftBridge reloaded with: " + enabledFeatureNames());
    }

    public String enabledFeatureNames() {
        if (features.isEmpty()) {
            return "(no features enabled)";
        }
        List<String> names = new ArrayList<>();
        for (Feature f : features) {
            names.add(f.name());
        }
        return String.join(", ", names);
    }

    public CraftBridgeConfig config() {
        return config;
    }

    public ContainerAccess containerAccess() {
        return containerAccess;
    }

    /** Log at DEBUG, or at INFO when {@code debug: true} is set in config.yml. */
    public void debug(String message) {
        if (config != null && config.debug()) {
            getLogger().info("[debug] " + message);
        } else {
            getSLF4JLogger().debug(message);
        }
    }

    /** A toggleable unit of functionality. */
    public interface Feature {
        String name();

        void enable();

        void disable();
    }
}
