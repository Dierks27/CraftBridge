package com.dierks.craftbridge.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load a data file the plugin also writes back, without {@link YamlConfiguration#loadConfiguration(File)}.
 *
 * <p>{@code loadConfiguration} swallows a syntax error: it logs it and hands back an EMPTY
 * configuration, indistinguishable from an empty file. For a store that rewrites its whole file
 * on the next change that is fatal — one typo in a hand edit, then one GUI save, and every entry
 * is gone for good. This loads with {@link YamlConfiguration#load(File)} instead, so the caller
 * learns the load failed and can refuse to save for the rest of the session.
 */
public final class SafeYaml {

    private SafeYaml() {
    }

    /**
     * The parsed file; an empty configuration when the file does not exist; or {@code null}
     * (after logging SEVERE) when it exists but cannot be read or is not valid YAML. A
     * {@code null} means "do not save over this file".
     */
    public static YamlConfiguration loadOrNull(File file, Logger logger) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) {
            return yaml;
        }
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            logger.log(Level.SEVERE, file.getName() + " could not be loaded, so nothing in it is active and "
                    + "CraftBridge will NOT save over it this session (changes made in-game are kept in memory "
                    + "only). Fix the file and run /craftbridge reload. Cause: " + ex.getMessage());
            return null;
        }
    }

    /** The SEVERE line a store logs when it skips a save because its file failed to load. */
    public static void refuseSave(File file, Logger logger) {
        logger.severe("Not saving " + file.getName() + ": it failed to load this session and saving now "
                + "would overwrite it. Fix the file and run /craftbridge reload; this change is in memory only.");
    }
}
