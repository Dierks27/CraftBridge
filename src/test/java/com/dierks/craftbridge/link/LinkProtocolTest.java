package com.dierks.craftbridge.link;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both codebases keep a copy of the protocol, so every payload's round trip is pinned here.
 * A mismatch between plugin and mod is the one failure mode that would be invisible in-game
 * until items moved wrongly.
 */
class LinkProtocolTest {

    private static byte[] item(int id) {
        return new byte[]{1, 2, (byte) id};
    }

    @Test
    void helloRoundTripsBothWays() {
        LinkProtocol.ClientHello client = LinkProtocol.decodeClientHello(
                LinkProtocol.encode(new LinkProtocol.ClientHello("0.1.0")));
        assertEquals("0.1.0", client.modVersion());

        LinkProtocol.ServerHello server = LinkProtocol.decodeServerHello(
                LinkProtocol.encode(new LinkProtocol.ServerHello("0.7", LinkProtocol.FLAG_PHANTOM_SLOTS_OFF)));
        assertEquals("0.7", server.pluginVersion());
        assertTrue(server.phantomSlotsOff());
        assertFalse(LinkProtocol.decodeServerHello(
                LinkProtocol.encode(new LinkProtocol.ServerHello("0.7", 0))).phantomSlotsOff());
    }

    @Test
    void aFullSnapshotRoundTrips() {
        LinkProtocol.Storage sent = new LinkProtocol.Storage(7, true,
                List.of(new LinkProtocol.Entry(item(1), 1204), new LinkProtocol.Entry(item(2), 3)));
        LinkProtocol.Storage got = LinkProtocol.decodeStorage(LinkProtocol.encode(sent));
        assertEquals(7, got.sequence());
        assertTrue(got.full());
        assertEquals(2, got.entries().size());
        assertArrayEquals(item(1), got.entries().get(0).item());
        assertEquals(1204, got.entries().get(0).count());
    }

    @Test
    void aDeltaCarriesRemovalsAsZero() {
        LinkProtocol.Storage got = LinkProtocol.decodeStorage(LinkProtocol.encode(
                new LinkProtocol.Storage(8, false, List.of(new LinkProtocol.Entry(item(2), 0)))));
        assertFalse(got.full());
        assertEquals(0, got.entries().get(0).count());
    }

    @Test
    void aTransferRequestWithARecipeIdCarriesNothingElse() {
        LinkProtocol.TransferRequest got = LinkProtocol.decodeTransferRequest(LinkProtocol.encode(
                new LinkProtocol.TransferRequest(3, 8, true, false, "minecraft:torch", List.of())));
        assertEquals(3, got.requestId());
        assertEquals(8, got.basedOnSequence());
        assertTrue(got.maxTransfer());
        assertFalse(got.requireCompleteSets());
        assertEquals("minecraft:torch", got.recipeId());
        assertTrue(got.slots().isEmpty());
    }

    @Test
    void aTransferRequestWithoutAnIdCarriesTheSlotChoices() {
        LinkProtocol.TransferRequest sent = new LinkProtocol.TransferRequest(4, 9, false, true, "",
                List.of(new LinkProtocol.SlotChoices(0, List.of(item(1), item(2))),
                        new LinkProtocol.SlotChoices(4, List.of(item(3)))));
        LinkProtocol.TransferRequest got = LinkProtocol.decodeTransferRequest(LinkProtocol.encode(sent));
        assertEquals("", got.recipeId());
        assertEquals(2, got.slots().size());
        assertEquals(4, got.slots().get(1).gridIndex());
        assertArrayEquals(item(2), got.slots().get(0).choices().get(1));
    }

    @Test
    void resultsAndSessionEndRoundTrip() {
        LinkProtocol.TransferResult result = LinkProtocol.decodeTransferResult(LinkProtocol.encode(
                new LinkProtocol.TransferResult(3, false, "A chest was emptied while you were looking.")));
        assertFalse(result.ok());
        assertEquals("A chest was emptied while you were looking.", result.message());
        assertEquals("out of range", LinkProtocol.decodeSessionEnd(
                LinkProtocol.encode(new LinkProtocol.SessionEnd("out of range"))).reason());
        assertEquals(11, LinkProtocol.decodeResync(LinkProtocol.encode(new LinkProtocol.Resync(11))).lastSequence());
    }

    @Test
    void theItemCatalogCarriesNamesAndDistinguishingKeys() {
        LinkProtocol.ItemCatalog got = LinkProtocol.decodeItemCatalog(LinkProtocol.encode(
                new LinkProtocol.ItemCatalog(List.of(
                        new LinkProtocol.CatalogEntry(item(9), "PC", List.of("homecraftmanagement:block_id"))))));
        assertEquals(1, got.entries().size());
        assertEquals("PC", got.entries().get(0).displayName());
        assertEquals(List.of("homecraftmanagement:block_id"), got.entries().get(0).distinguishingKeys());
    }

    @Test
    void aVersionMismatchRefusesRatherThanMisreads() {
        byte[] payload = LinkProtocol.encode(new LinkProtocol.SessionEnd("closed"));
        payload[0] = (byte) (LinkProtocol.VERSION + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LinkProtocol.decodeSessionEnd(payload));
        assertTrue(ex.getMessage().contains("version"), ex.getMessage());
    }

    @Test
    void aTruncatedPayloadIsRejected() {
        byte[] payload = LinkProtocol.encode(new LinkProtocol.Storage(1, true,
                List.of(new LinkProtocol.Entry(item(1), 5))));
        byte[] cut = java.util.Arrays.copyOf(payload, payload.length - 2);
        assertThrows(IllegalArgumentException.class, () -> LinkProtocol.decodeStorage(cut));
    }

    @Test
    void aFullSnapshotOfARealBaseIsMeasured() {
        // The sizing question the handoff raised: hundreds to low thousands of types in range.
        // Item blobs are the bulk of it, so this measures framing plus a representative stack.
        List<LinkProtocol.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            entries.add(new LinkProtocol.Entry(new byte[24], 1 + i));
        }
        int bytes = LinkProtocol.estimatedBytes(new LinkProtocol.Storage(1, true, entries));
        // Comfortably inside the 1 MiB custom-payload ceiling, so compression is not needed
        // for the snapshot itself; deltas keep the steady state far smaller again.
        assertTrue(bytes < 1024 * 1024, "2000 types took " + bytes + " bytes");
        assertTrue(bytes > 2000 * 24, "sanity: the item blobs must actually be in there");
    }
}
