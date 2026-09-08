package com.dierks.craftbridge.jei;

import java.util.List;

/**
 * JEI 26.2 (JustEnoughItems branch {@code 26.2}, tag {@code v26.2.0}) uses one custom
 * payload id per packet type — there is no shared channel with a packet-id prefix, and
 * no version handshake. Ids are {@code jei:<name>}; the Fabric client decides
 * {@code isJeiOnServer} purely from whether the server announced
 * {@code jei:delete_player_item} in its {@code minecraft:register} list (which Paper
 * sends for every channel a plugin registered as <em>incoming</em>).
 *
 * <p>Only the recipe-transfer family is handled here. The cheat-mode packets
 * ({@code give_item_stack}, {@code set_hotbar_item_stack}, {@code request_cheat_permission},
 * {@code cheat_permission}) are intentionally <b>not</b> registered: JEI only sends a packet
 * when the server declared the channel, so cheat mode stays inert.
 */
public final class JeiChannels {

    /** JEI version whose network code this was written against. */
    public static final String BUILT_AGAINST = "JEI 26.2.0 (Minecraft 26.2)";

    /** Legacy: ops without counts, no reply expected. */
    public static final String RECIPE_TRANSFER = "jei:recipe_transfer";
    /** Legacy: ops with counts, no reply expected. */
    public static final String RECIPE_TRANSFER_COUNTED = "jei:recipe_transfer_counted";
    /** Current: ops without counts + transferId; server replies on {@link #RECIPE_TRANSFER_RESULT}. */
    public static final String RECIPE_TRANSFER_WITH_RESULT = "jei:recipe_transfer_with_result";
    /** Current: ops with counts + transferId; server replies on {@link #RECIPE_TRANSFER_RESULT}. */
    public static final String RECIPE_TRANSFER_COUNTED_WITH_RESULT = "jei:recipe_transfer_counted_with_result";
    /** Clientbound reply: VarInt transferId, boolean success. */
    public static final String RECIPE_TRANSFER_RESULT = "jei:recipe_transfer_result";
    /**
     * The presence marker JEI's client checks ({@code ClientPlayNetworking.canSend(PacketDeletePlayerItem.TYPE)}).
     * Registered incoming so the marker is announced; any payload on it is ignored
     * (JEIServerProxy handles it for ops if that plugin is installed).
     */
    public static final String DELETE_PLAYER_ITEM = "jei:delete_player_item";

    public static final List<String> INCOMING = List.of(
            RECIPE_TRANSFER, RECIPE_TRANSFER_COUNTED, RECIPE_TRANSFER_WITH_RESULT,
            RECIPE_TRANSFER_COUNTED_WITH_RESULT, DELETE_PLAYER_ITEM);
    public static final List<String> OUTGOING = List.of(RECIPE_TRANSFER_RESULT);

    private JeiChannels() {
    }

    public static boolean isCounted(String channel) {
        return RECIPE_TRANSFER_COUNTED.equals(channel) || RECIPE_TRANSFER_COUNTED_WITH_RESULT.equals(channel);
    }

    public static boolean hasResult(String channel) {
        return RECIPE_TRANSFER_WITH_RESULT.equals(channel) || RECIPE_TRANSFER_COUNTED_WITH_RESULT.equals(channel);
    }

    public static boolean isTransfer(String channel) {
        return RECIPE_TRANSFER.equals(channel) || RECIPE_TRANSFER_COUNTED.equals(channel)
                || RECIPE_TRANSFER_WITH_RESULT.equals(channel) || RECIPE_TRANSFER_COUNTED_WITH_RESULT.equals(channel);
    }
}
