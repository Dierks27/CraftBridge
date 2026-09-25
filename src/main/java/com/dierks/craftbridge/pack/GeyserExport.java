package com.dierks.craftbridge.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /craftbridge geyser export}: writes the Bedrock pack and the Geyser mappings into
 * {@code plugins/CraftBridge/geyser/}, and copies them into Geyser's own folders when Geyser
 * runs on this server. No Bukkit here, so it is tested on a temporary directory.
 */
public final class GeyserExport {

    public static final String MCPACK = "CraftBridge.mcpack";
    public static final String MAPPINGS = "craftbridge_mappings.json";
    public static final String DISPLAY_MAPPINGS = "geyserdisplayentity_craftbridge.yml";
    /** What the display-entity mapping is called inside GeyserDisplayEntity's Mappings folder. */
    static final String DISPLAY_MAPPINGS_TARGET = "craftbridge.yml";

    /** Geyser's data folder on Paper/Spigot, next to plugins/CraftBridge. */
    static final String GEYSER_FOLDER = "Geyser-Spigot";
    /** The GeyserDisplayEntity extension's data folder inside Geyser's. */
    static final String DISPLAY_EXTENSION = "extensions/geyserdisplayentity";

    private GeyserExport() {
    }

    /**
     * @param bedrockPack files of the Bedrock pack (manifest.json at the root)
     * @param mappings    the Geyser v2 item mapping json
     * @param displayMappings the GeyserDisplayEntity mapping yml
     * @param outDir      plugins/CraftBridge/geyser
     * @param pluginsDir  the server's plugins folder, where Geyser-Spigot may live
     * @return one line per thing done, for the command sender and the log
     */
    public static List<String> export(Map<String, byte[]> bedrockPack, byte[] mappings, byte[] displayMappings,
                                      Path outDir, Path pluginsDir) throws IOException {
        List<String> done = new ArrayList<>();
        Files.createDirectories(outDir);
        byte[] mcpack = PackFiles.zip(bedrockPack);
        write(outDir.resolve(MCPACK), mcpack);
        write(outDir.resolve(MAPPINGS), mappings);
        write(outDir.resolve(DISPLAY_MAPPINGS), displayMappings);
        done.add("Wrote " + MCPACK + ", " + MAPPINGS + " and " + DISPLAY_MAPPINGS + " to " + outDir);

        Path geyser = pluginsDir.resolve(GEYSER_FOLDER);
        if (!Files.isDirectory(geyser)) {
            done.add("No " + GEYSER_FOLDER + " folder here (Geyser may run on the proxy): copy " + MCPACK
                    + " into Geyser's packs/ folder and " + MAPPINGS + " into its custom_mappings/ folder yourself.");
            return done;
        }
        write(geyser.resolve("packs").resolve(MCPACK), mcpack);
        write(geyser.resolve("custom_mappings").resolve(MAPPINGS), mappings);
        done.add("Copied them into " + GEYSER_FOLDER + "/packs and " + GEYSER_FOLDER + "/custom_mappings.");
        Path extension = geyser.resolve(DISPLAY_EXTENSION);
        if (Files.isDirectory(extension)) {
            write(extension.resolve("Mappings").resolve(DISPLAY_MAPPINGS_TARGET), displayMappings);
            done.add("Copied the display mapping into " + GEYSER_FOLDER + "/" + DISPLAY_EXTENSION + "/Mappings.");
        } else {
            done.add("GeyserDisplayEntity is not installed, so Bedrock players see the items but not the block models.");
        }
        done.add("Restart the server (Geyser reads packs and mappings only at startup). Geyser's config needs"
                + " enable-custom-content: true.");
        return done;
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
