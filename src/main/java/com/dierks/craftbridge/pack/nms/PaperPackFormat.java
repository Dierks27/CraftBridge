package com.dierks.craftbridge.pack.nms;

import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;

/**
 * The resource pack format of the Minecraft version this server runs, read at run time from the
 * server's own version data. {@code SharedConstants.RESOURCE_PACK_FORMAT_MAJOR} would be the
 * number of the dev bundle CraftBridge was compiled against (javac copies constants into the
 * class), not the server's.
 */
public final class PaperPackFormat {

    private PaperPackFormat() {
    }

    /** {@code {major, minor}}, e.g. {@code {97, 1}}. */
    public static int[] current() {
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        return new int[]{format.major(), format.minor()};
    }
}
