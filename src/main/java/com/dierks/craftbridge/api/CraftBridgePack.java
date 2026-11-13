package com.dierks.craftbridge.api;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/**
 * The server's one resource pack, built and sent by CraftBridge. Other plugins add their art to
 * it and get font characters (glyphs) for icons and textured menus from it.
 *
 * <p>Look it up on enable, after declaring {@code softdepend: [CraftBridge]} in plugin.yml, and
 * do nothing when it is missing:
 * <pre>{@code
 * CraftBridgePack pack = Bukkit.getServicesManager().load(CraftBridgePack.class);
 * if (pack != null) {
 *     pack.registerSource(this, "hcm", "pack/");   // assets/hcm/... inside this plugin's jar
 *     String coin = pack.glyph("hcm:coin");        // the same character on every start
 * }
 * }</pre>
 *
 * <p>The pack is built once every plugin has enabled, so a source registered in
 * {@code onEnable} is in the first build without asking. Call {@link #requestRebuild()} only
 * when your art changes while the server runs.
 *
 * <p>This interface is the whole API. It only grows (new methods get defaults), so a plugin
 * built against it keeps working with later CraftBridge versions.
 */
public interface CraftBridgePack {

    /** Bumped when a method is added. 1 = CraftBridge 0.19.0. */
    int API_VERSION = 1;

    /**
     * Add a folder laid out like a resource pack ({@code assets/<namespace>/...}, optionally a
     * {@code glyphs.json} at its root) to the pack. Read at every build, so a change to the files
     * shows on the next {@link #requestRebuild()} or {@code /craftbridge pack rebuild}.
     *
     * @param plugin   the plugin the art belongs to; its source leaves the pack when it is disabled
     * @param sourceId a short lower-case id ({@code [a-z0-9_.-]}), usually your namespace, e.g. {@code hcm};
     *                 the name {@code pack.priority} in CraftBridge's config.yml and every log line uses
     * @param folder   the folder holding {@code assets/}
     * @throws IllegalArgumentException when the id is not usable, or another plugin registered it
     */
    void registerSource(Plugin plugin, String sourceId, Path folder);

    /**
     * Add a folder inside the plugin's own jar, e.g. {@code "pack/"} for the jar entries
     * {@code pack/assets/hcm/...} (from {@code src/main/resources/pack/}).
     *
     * @param jarRoot the folder in the jar, with or without the trailing slash; {@code ""} for the jar's root
     * @see #registerSource(Plugin, String, Path)
     */
    void registerSource(Plugin plugin, String sourceId, String jarRoot);

    /**
     * The character for a glyph key ({@code namespace:name}, e.g. {@code hcm:coin}), to put in
     * titles, item names and lore, chat, scoreboards and LuckPerms prefixes. A key keeps its
     * character for good: the first call (or the first build that finds it in a
     * {@code glyphs.json}) picks one and stores it in {@code plugins/CraftBridge/pack/glyphs.yml}.
     *
     * <p>The character draws as the glyph's texture for players who loaded the pack and as a
     * box for everyone else. It is in the default font, so no font needs setting on the text.
     *
     * @throws IllegalArgumentException when the key is not {@code namespace:name} in lower case
     */
    String glyph(String key);

    /**
     * Characters that move the text after them by {@code pixels} (negative: to the left), for
     * placing a menu background or lining text up. Built from the fixed space glyphs
     * ({@code craftbridge:space/-1} ... {@code craftbridge:space/1024}); empty for 0.
     */
    String space(int pixels);

    /**
     * Ask for the pack to be built again and re-sent to everyone online. Requests close
     * together are combined into one build on the next tick. Before the first build (while the
     * server is still starting) this does nothing: that build reads every source anyway.
     */
    void requestRebuild();
}
