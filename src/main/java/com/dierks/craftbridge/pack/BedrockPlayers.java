package com.dierks.craftbridge.pack;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Tells Bedrock players (joining through Geyser) from Java players, using Floodgate's or
 * Geyser's API when either is installed on this server. Both are looked up by reflection,
 * so neither is a dependency. Without them every player counts as Java; a Bedrock player
 * then declines the Java pack (Geyser answers every optional pack with "declined") and
 * sees the plain blocks, which is the safe default.
 */
public final class BedrockPlayers {

    private final Object floodgate;
    private final Method isFloodgatePlayer;
    private final Object geyser;
    private final Method isBedrockPlayer;

    private BedrockPlayers(Object floodgate, Method isFloodgatePlayer, Object geyser, Method isBedrockPlayer) {
        this.floodgate = floodgate;
        this.isFloodgatePlayer = isFloodgatePlayer;
        this.geyser = geyser;
        this.isBedrockPlayer = isBedrockPlayer;
    }

    /** Find whichever API is present. {@code loader} should see other plugins' classes (the plugin's own loader does). */
    public static BedrockPlayers detect(ClassLoader loader, Logger log) {
        Object floodgate = null;
        Method isFloodgatePlayer = null;
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            floodgate = api.getMethod("getInstance").invoke(null);
            isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            floodgate = null;
        }
        Object geyser = null;
        Method isBedrockPlayer = null;
        try {
            Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi", true, loader);
            geyser = api.getMethod("api").invoke(null);
            isBedrockPlayer = api.getMethod("isBedrockPlayer", UUID.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            geyser = null;
        }
        BedrockPlayers found = new BedrockPlayers(floodgate, isFloodgatePlayer, geyser, isBedrockPlayer);
        if (log != null) {
            log.info("Resource pack: Bedrock players are recognised by "
                    + (floodgate != null ? "Floodgate" : geyser != null ? "Geyser" : "nothing (no Floodgate or Geyser on this server)")
                    + ".");
        }
        return found;
    }

    /** Whether any API was found. */
    public boolean available() {
        return floodgate != null || geyser != null;
    }

    /** True for a Bedrock player; false for a Java player, or when it cannot be told. */
    public boolean isBedrock(UUID player) {
        if (player == null) {
            return false;
        }
        try {
            if (floodgate != null && Boolean.TRUE.equals(isFloodgatePlayer.invoke(floodgate, player))) {
                return true;
            }
            return geyser != null && Boolean.TRUE.equals(isBedrockPlayer.invoke(geyser, player));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
