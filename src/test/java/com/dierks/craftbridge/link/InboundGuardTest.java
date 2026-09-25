package com.dierks.craftbridge.link;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-player budget on link messages, and telling a version mismatch from a bad packet. */
class InboundGuardTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aHelloIsHandledAtMostOnceASecond() {
        InboundGuard guard = new InboundGuard();
        long t = 5 * SECOND;
        assertTrue(guard.allow(LinkProtocol.CHANNEL_HELLO, t));
        assertFalse(guard.allow(LinkProtocol.CHANNEL_HELLO, t + SECOND / 10));
        assertFalse(guard.allow(LinkProtocol.CHANNEL_HELLO, t + SECOND / 2));
        assertTrue(guard.allow(LinkProtocol.CHANNEL_HELLO, t + SECOND + SECOND / 10));
    }

    @Test
    void aResyncFloodCostsOneScanASecond() {
        InboundGuard guard = new InboundGuard();
        int handled = 0;
        // 1000 resyncs over ten seconds.
        for (int i = 0; i < 1000; i++) {
            if (guard.allow(LinkProtocol.CHANNEL_RESYNC, i * (SECOND / 100))) {
                handled++;
            }
        }
        assertTrue(handled >= 9 && handled <= 11, "handled " + handled);
    }

    @Test
    void fastHonestClickingIsNeverDropped() {
        // Five panel clicks and two [+] presses a second for a minute.
        InboundGuard guard = new InboundGuard();
        for (int i = 0; i < 300; i++) {
            assertTrue(guard.allow(LinkProtocol.CHANNEL_PULL_REQUEST, i * (SECOND / 5)), "pull " + i);
        }
        for (int i = 0; i < 120; i++) {
            assertTrue(guard.allow(LinkProtocol.CHANNEL_TRANSFER_REQUEST, i * (SECOND / 2)), "transfer " + i);
        }
    }

    @Test
    void middleClickSortingIsThrottledButAnHonestPlayerIsNot() {
        InboundGuard guard = new InboundGuard();
        // One middle-click a second for a minute: never dropped.
        for (int i = 0; i < 60; i++) {
            assertTrue(guard.allow(LinkProtocol.CHANNEL_SORT_REQUEST, i * SECOND), "sort " + i);
        }
        // A macro hammering it: the burst, then nothing until the bucket refills.
        InboundGuard flooded = new InboundGuard();
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (flooded.allow(LinkProtocol.CHANNEL_SORT_REQUEST, 0)) {
                allowed++;
            }
        }
        assertTrue(allowed == 3, "allowed " + allowed);
    }

    @Test
    void aBurstIsAllowedThenThrottled() {
        InboundGuard guard = new InboundGuard();
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (guard.allow(LinkProtocol.CHANNEL_TRANSFER_REQUEST, 0)) {
                allowed++;
            }
        }
        assertTrue(allowed == 10, "burst of " + allowed);
        assertTrue(guard.allow(LinkProtocol.CHANNEL_TRANSFER_REQUEST, SECOND / 2), "refilled after half a second");
    }

    @Test
    void kindsHaveSeparateBudgets() {
        InboundGuard guard = new InboundGuard();
        assertTrue(guard.allow(LinkProtocol.CHANNEL_HELLO, 0));
        assertTrue(guard.allow(LinkProtocol.CHANNEL_RESYNC, 0));
        assertTrue(guard.allow(LinkProtocol.CHANNEL_PULL_REQUEST, 0));
        assertTrue(guard.allow("some:other_channel", 0));
        assertTrue(guard.allow("some:other_channel", 0));
    }

    @Test
    void dropsAreReportedAtMostOnceASecond() {
        InboundGuard guard = new InboundGuard();
        assertTrue(guard.shouldReportDrop(0));
        assertFalse(guard.shouldReportDrop(SECOND / 2));
        assertTrue(guard.shouldReportDrop(SECOND + 1));
    }

    @Test
    void aTruncatedPayloadIsABadPacketNotAVersionMismatch() {
        byte[] payload = LinkProtocol.encode(new LinkProtocol.TransferRequest(3, 1, true, true, "",
                List.of(new LinkProtocol.SlotChoices(0, List.of(new byte[]{1, 2, 3})))));
        byte[] cut = Arrays.copyOf(payload, payload.length - 2);
        assertThrows(IllegalArgumentException.class, () -> LinkProtocol.decodeTransferRequest(cut));
        assertFalse(InboundGuard.isVersionMismatch(cut));
        assertFalse(InboundGuard.isVersionMismatch(new byte[0]));
        assertFalse(InboundGuard.isVersionMismatch(new byte[]{(byte) 0x80})); // a VarInt cut short
    }

    @Test
    void anotherVersionIsAVersionMismatch() {
        byte[] payload = LinkProtocol.encode(new LinkProtocol.ClientHello("0.1.0"));
        payload[0] = (byte) (LinkProtocol.VERSION + 1);
        assertThrows(IllegalArgumentException.class, () -> LinkProtocol.decodeClientHello(payload));
        assertTrue(InboundGuard.isVersionMismatch(payload));
    }
}
