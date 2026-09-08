package com.dierks.craftbridge.jei;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded recipe-transfer request. Wire layout (all four channels share it; the two
 * "counted" variants add a VarInt count per operation, the two "with_result" variants
 * append a VarInt transferId):
 * <pre>
 *   VarInt opCount, then per op: VarInt inventorySlotId, VarInt craftingSlotId[, VarInt count]
 *   VarInt n, then n × VarInt craftingSlotId          (the recipe's target slots)
 *   VarInt n, then n × VarInt inventorySlotId         (slots the server may draw from)
 *   boolean maxTransfer                               (shift-click [+] = fill as many sets as possible)
 *   boolean requireCompleteSets
 *   [VarInt transferId]
 * </pre>
 * Slot ids are the vanilla container-menu slot indexes (raw slots): for the crafting
 * table 0 = result, 1-9 = grid, 10-45 = player inventory; for the player's own 2x2 grid
 * 0 = result, 1-4 = grid, 9-44 = inventory. No ItemStacks are transmitted — the server
 * reads them from the slots — so no NMS codec is needed to decode this.
 */
public record TransferPacket(List<Op> ops, List<Integer> craftingSlots, List<Integer> inventorySlots,
                             boolean maxTransfer, boolean requireCompleteSets, Integer transferId) {

    /** Move {@code count} items from {@code inventorySlot} to {@code craftingSlot}. */
    public record Op(int inventorySlot, int craftingSlot, int count) {
    }

    private static final int MAX_LIST = 256;

    public boolean expectsResult() {
        return transferId != null;
    }

    public static TransferPacket decode(byte[] data, boolean counted, boolean withResult) {
        VarInts.Reader in = new VarInts.Reader(data);
        int opCount = readSize(in, "operations");
        List<Op> ops = new ArrayList<>(opCount);
        for (int i = 0; i < opCount; i++) {
            int inv = in.readVarInt();
            int craft = in.readVarInt();
            int count = counted ? in.readVarInt() : 1;
            if (count < 1) {
                throw new IllegalArgumentException("operation count must be positive, got " + count);
            }
            ops.add(new Op(inv, craft, count));
        }
        List<Integer> crafting = readIntList(in, "crafting slots");
        List<Integer> inventory = readIntList(in, "inventory slots");
        boolean maxTransfer = in.readBoolean();
        boolean requireCompleteSets = in.readBoolean();
        Integer transferId = withResult ? in.readVarInt() : null;
        if (in.hasRemaining()) {
            throw new IllegalArgumentException(in.remaining() + " trailing byte(s) after packet");
        }
        return new TransferPacket(ops, crafting, inventory, maxTransfer, requireCompleteSets, transferId);
    }

    private static int readSize(VarInts.Reader in, String what) {
        int n = in.readVarInt();
        if (n < 0 || n > MAX_LIST) {
            throw new IllegalArgumentException("unreasonable " + what + " list size " + n);
        }
        return n;
    }

    private static List<Integer> readIntList(VarInts.Reader in, String what) {
        int n = readSize(in, what);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(in.readVarInt());
        }
        return out;
    }

    /** Encode the reply JEI's client waits for on {@code jei:recipe_transfer_result}. */
    public static byte[] encodeResult(int transferId, boolean success) {
        return new VarInts.Writer().writeVarInt(transferId).writeBoolean(success).toByteArray();
    }
}
