package com.dierks.craftbridge.jei;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Byte layouts taken from JEI's own PacketRecipeTransferCodecTest (branch 26.2). */
class TransferPacketTest {

    @Test
    void decodesLegacyUncountedPacketExactlyLikeJei() {
        // PacketRecipeTransfer(ops=[(inv 3 -> craft 4)], crafting=[4], inventory=[3], max=false, complete=true)
        byte[] bytes = {1, 3, 4, 1, 4, 1, 3, 0, 1};
        TransferPacket p = TransferPacket.decode(bytes, false, false);
        assertEquals(List.of(new TransferPacket.Op(3, 4, 1)), p.ops());
        assertEquals(List.of(4), p.craftingSlots());
        assertEquals(List.of(3), p.inventorySlots());
        assertFalse(p.maxTransfer());
        assertTrue(p.requireCompleteSets());
        assertNull(p.transferId());
        assertFalse(p.expectsResult());
    }

    @Test
    void decodesLegacyCountedPacketExactlyLikeJei() {
        // PacketRecipeTransferCounted(ops=[(3 -> 4, count 2)], crafting=[4], inventory=[3], max=true, complete=false)
        byte[] bytes = {1, 3, 4, 2, 1, 4, 1, 3, 1, 0};
        TransferPacket p = TransferPacket.decode(bytes, true, false);
        assertEquals(List.of(new TransferPacket.Op(3, 4, 2)), p.ops());
        assertTrue(p.maxTransfer());
        assertFalse(p.requireCompleteSets());
    }

    @Test
    void decodesWithResultVariantsAndTransferId() {
        byte[] bytes = {1, 3, 4, 1, 4, 1, 3, 0, 1, 42};
        TransferPacket p = TransferPacket.decode(bytes, false, true);
        assertEquals(42, p.transferId());
        byte[] counted = {1, 3, 4, 2, 1, 4, 1, 3, 1, 0, 43};
        assertEquals(43, TransferPacket.decode(counted, true, true).transferId());
    }

    @Test
    void multiByteVarIntsRoundTrip() {
        byte[] bytes = new VarInts.Writer().writeVarInt(1).writeVarInt(300).writeVarInt(45)
                .writeVarInt(1).writeVarInt(45).writeVarInt(1).writeVarInt(300)
                .writeBoolean(true).writeBoolean(true).writeVarInt(70000).toByteArray();
        TransferPacket p = TransferPacket.decode(bytes, false, true);
        assertEquals(300, p.ops().getFirst().inventorySlot());
        assertEquals(70000, p.transferId());
    }

    @Test
    void rejectsTruncatedTrailingAndAbsurdPayloads() {
        assertThrows(IllegalArgumentException.class, () -> TransferPacket.decode(new byte[]{1, 3}, false, false));
        assertThrows(IllegalArgumentException.class, () -> TransferPacket.decode(new byte[]{1, 3, 4, 1, 4, 1, 3, 0, 1, 9}, false, false));
        assertThrows(IllegalArgumentException.class, () -> TransferPacket.decode(new byte[]{(byte) 0xFF, (byte) 0xFF, 0x7F}, false, false));
        assertThrows(IllegalArgumentException.class, () -> TransferPacket.decode(new byte[]{1, 3, 4, 0, 1, 4, 1, 3, 0, 1}, true, false));
    }

    @Test
    void encodesResultAsVarIntIdPlusBoolean() {
        assertArrayEquals(new byte[]{42, 1}, TransferPacket.encodeResult(42, true));
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02, 0}, TransferPacket.encodeResult(300, false));
    }
}
