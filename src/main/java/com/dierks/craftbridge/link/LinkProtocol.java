package com.dierks.craftbridge.link;

import com.dierks.craftbridge.jei.VarInts;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire protocol between the CraftBridge plugin and the optional CraftBridge-Client mod.
 *
 * <p>A vanilla crafting menu has 36 inventory slots and JEI decides craftability by scanning
 * them, so a server on its own can never show more than 36 item types. The mod removes that
 * ceiling by being told what is in storage directly. This class is the contract: <b>the two
 * codebases keep byte-identical copies of it</b>, and every payload starts with
 * {@link #VERSION} so a mismatched pair refuses to talk rather than misreading each other.
 *
 * <p>Item stacks are carried as opaque length-prefixed blobs, encoded by the game's own item
 * codec on whichever side is writing. That keeps this class free of both Bukkit and Minecraft
 * — it is pure framing, so it can be unit tested and shared verbatim.
 *
 * <h2>Sizing</h2>
 * A real base has hundreds to low thousands of distinct types in range of one workbench, and
 * the storage view is resent after every transfer and craft, so a full snapshot every time
 * would be wasteful. {@link Storage} is therefore either a full snapshot or a delta, each
 * carrying a sequence number: the client applies deltas in order and asks for a full snapshot
 * with {@link Resync} the moment it sees a gap. A count of zero in a delta means the type is
 * gone. Compression is deliberately not specified until the full snapshot has been measured
 * against a base of that size — {@link #estimatedBytes} exists to do that measuring.
 */
public final class LinkProtocol {

    /** Bumped whenever any payload's layout changes. Both sides must agree exactly. */
    public static final int VERSION = 1;

    public static final String CHANNEL_HELLO = "craftbridge:hello";
    public static final String CHANNEL_STORAGE = "craftbridge:storage";
    public static final String CHANNEL_RESYNC = "craftbridge:resync";
    public static final String CHANNEL_TRANSFER_REQUEST = "craftbridge:transfer_request";
    public static final String CHANNEL_TRANSFER_RESULT = "craftbridge:transfer_result";
    public static final String CHANNEL_SESSION_END = "craftbridge:session_end";
    public static final String CHANNEL_ITEM_CATALOG = "craftbridge:item_catalog";

    /** Set in {@link ServerHello#flags} when the server has turned phantom slots off for this player. */
    public static final int FLAG_PHANTOM_SLOTS_OFF = 1;

    private LinkProtocol() {
    }

    // ---- payloads --------------------------------------------------------------------

    /** Client announces itself on join. No reply means no CraftBridge, and the mod stays dormant. */
    public record ClientHello(String modVersion) {
    }

    /** The server's reply, which is also what switches the mod on. */
    public record ServerHello(String pluginVersion, int flags) {
        public boolean phantomSlotsOff() {
            return (flags & FLAG_PHANTOM_SLOTS_OFF) != 0;
        }
    }

    /** One item type and how many of it are in range. */
    public record Entry(byte[] item, int count) {
    }

    /**
     * @param sequence increments by one per message for a session; a client that sees a gap
     *                 asks for a full snapshot rather than guessing
     * @param full     true for a complete snapshot, false for changes only
     */
    public record Storage(int sequence, boolean full, List<Entry> entries) {
    }

    /** "I am at this sequence and I have fallen behind; send me everything." */
    public record Resync(int lastSequence) {
    }

    /** One crafting-grid slot's acceptable items, for a recipe with no id of its own. */
    public record SlotChoices(int gridIndex, List<byte[]> choices) {
    }

    /**
     * What the player asked for. The client says which recipe it wants and nothing more: it
     * never says what it has or how much, because the server does not trust it.
     *
     * @param basedOnSequence the storage sequence the client's craftability check used, so the
     *                        server can tell it to resync instead of acting on a stale view
     */
    public record TransferRequest(int requestId, int basedOnSequence, boolean maxTransfer,
                                  boolean requireCompleteSets, String recipeId, List<SlotChoices> slots) {
    }

    /** Success, or why not — shown as a JEI transfer error tooltip rather than silence. */
    public record TransferResult(int requestId, boolean ok, String message) {
    }

    /** The session is over: drop the cached snapshot. */
    public record SessionEnd(String reason) {
    }

    /**
     * Custom items the server knows about, so the mod can register JEI subtypes for them and
     * give them their own tiles in the item list. Renamed vanilla items carrying PDC are not
     * distinct registry entries, so without this there is nothing to look their recipe up from.
     */
    public record CatalogEntry(byte[] item, String displayName, List<String> distinguishingKeys) {
    }

    public record ItemCatalog(List<CatalogEntry> entries) {
    }

    // ---- encoding --------------------------------------------------------------------

    public static byte[] encode(ClientHello hello) {
        return header().writeString(hello.modVersion()).toByteArray();
    }

    public static byte[] encode(ServerHello hello) {
        return header().writeString(hello.pluginVersion()).writeVarInt(hello.flags()).toByteArray();
    }

    public static byte[] encode(Storage storage) {
        VarInts.Writer w = header().writeVarInt(storage.sequence()).writeBoolean(storage.full());
        w.writeVarInt(storage.entries().size());
        for (Entry entry : storage.entries()) {
            w.writeBytes(entry.item()).writeVarInt(entry.count());
        }
        return w.toByteArray();
    }

    public static byte[] encode(Resync resync) {
        return header().writeVarInt(resync.lastSequence()).toByteArray();
    }

    public static byte[] encode(TransferRequest request) {
        VarInts.Writer w = header()
                .writeVarInt(request.requestId())
                .writeVarInt(request.basedOnSequence())
                .writeBoolean(request.maxTransfer())
                .writeBoolean(request.requireCompleteSets());
        boolean hasId = request.recipeId() != null && !request.recipeId().isEmpty();
        w.writeBoolean(hasId);
        if (hasId) {
            w.writeString(request.recipeId());
            return w.toByteArray();
        }
        List<SlotChoices> slots = request.slots() == null ? List.of() : request.slots();
        w.writeVarInt(slots.size());
        for (SlotChoices slot : slots) {
            w.writeVarInt(slot.gridIndex()).writeVarInt(slot.choices().size());
            for (byte[] choice : slot.choices()) {
                w.writeBytes(choice);
            }
        }
        return w.toByteArray();
    }

    public static byte[] encode(TransferResult result) {
        return header().writeVarInt(result.requestId()).writeBoolean(result.ok())
                .writeString(result.message()).toByteArray();
    }

    public static byte[] encode(SessionEnd end) {
        return header().writeString(end.reason()).toByteArray();
    }

    public static byte[] encode(ItemCatalog catalog) {
        VarInts.Writer w = header().writeVarInt(catalog.entries().size());
        for (CatalogEntry entry : catalog.entries()) {
            w.writeBytes(entry.item()).writeString(entry.displayName());
            w.writeVarInt(entry.distinguishingKeys().size());
            for (String key : entry.distinguishingKeys()) {
                w.writeString(key);
            }
        }
        return w.toByteArray();
    }

    // ---- decoding --------------------------------------------------------------------

    public static ClientHello decodeClientHello(byte[] payload) {
        VarInts.Reader r = open(payload);
        return new ClientHello(r.readString());
    }

    public static ServerHello decodeServerHello(byte[] payload) {
        VarInts.Reader r = open(payload);
        return new ServerHello(r.readString(), r.readVarInt());
    }

    public static Storage decodeStorage(byte[] payload) {
        VarInts.Reader r = open(payload);
        int sequence = r.readVarInt();
        boolean full = r.readBoolean();
        int count = r.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(r.readBytes(), r.readVarInt()));
        }
        return new Storage(sequence, full, entries);
    }

    public static Resync decodeResync(byte[] payload) {
        return new Resync(open(payload).readVarInt());
    }

    public static TransferRequest decodeTransferRequest(byte[] payload) {
        VarInts.Reader r = open(payload);
        int requestId = r.readVarInt();
        int basedOn = r.readVarInt();
        boolean maxTransfer = r.readBoolean();
        boolean completeSets = r.readBoolean();
        if (r.readBoolean()) {
            return new TransferRequest(requestId, basedOn, maxTransfer, completeSets, r.readString(), List.of());
        }
        int slotCount = r.readVarInt();
        List<SlotChoices> slots = new ArrayList<>(Math.min(slotCount, 9));
        for (int i = 0; i < slotCount; i++) {
            int gridIndex = r.readVarInt();
            int choiceCount = r.readVarInt();
            List<byte[]> choices = new ArrayList<>(Math.min(choiceCount, 64));
            for (int c = 0; c < choiceCount; c++) {
                choices.add(r.readBytes());
            }
            slots.add(new SlotChoices(gridIndex, choices));
        }
        return new TransferRequest(requestId, basedOn, maxTransfer, completeSets, "", slots);
    }

    public static TransferResult decodeTransferResult(byte[] payload) {
        VarInts.Reader r = open(payload);
        return new TransferResult(r.readVarInt(), r.readBoolean(), r.readString());
    }

    public static SessionEnd decodeSessionEnd(byte[] payload) {
        return new SessionEnd(open(payload).readString());
    }

    public static ItemCatalog decodeItemCatalog(byte[] payload) {
        VarInts.Reader r = open(payload);
        int count = r.readVarInt();
        List<CatalogEntry> entries = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            byte[] item = r.readBytes();
            String name = r.readString();
            int keyCount = r.readVarInt();
            List<String> keys = new ArrayList<>(Math.min(keyCount, 32));
            for (int k = 0; k < keyCount; k++) {
                keys.add(r.readString());
            }
            entries.add(new CatalogEntry(item, name, keys));
        }
        return new ItemCatalog(entries);
    }

    /** Roughly what a full snapshot of this many types costs on the wire, for sizing decisions. */
    public static int estimatedBytes(Storage storage) {
        return encode(storage).length;
    }

    private static VarInts.Writer header() {
        return new VarInts.Writer().writeVarInt(VERSION);
    }

    /** Reads and checks the version so a mismatched pair refuses rather than misreads. */
    private static VarInts.Reader open(byte[] payload) {
        VarInts.Reader r = new VarInts.Reader(payload);
        int version = r.readVarInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("CraftBridge link protocol version " + version
                    + ", but this side speaks version " + VERSION + "; update whichever is older.");
        }
        return r;
    }
}
