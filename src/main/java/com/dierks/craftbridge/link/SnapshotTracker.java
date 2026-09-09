package com.dierks.craftbridge.link;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a storage view up to date from a full snapshot plus a stream of deltas, and says when
 * it has fallen behind. Pure logic, shared by both sides: the client applies what it is sent,
 * and the server uses the same class to work out what changed since the last message.
 *
 * <p>Deltas are only safe in order. Each message carries a sequence number one higher than the
 * last, so a gap — a dropped or reordered message — is detectable rather than silently
 * corrupting the view into something that would make JEI offer a craft the server will refuse.
 * On a gap the holder stops applying and asks for a full snapshot.
 */
public final class SnapshotTracker {

    /** An item stack's encoded bytes, with the value semantics a map key needs. */
    public record ItemKey(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ItemKey other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "ItemKey[" + bytes.length + " bytes]";
        }
    }

    private final Map<ItemKey, Integer> counts = new LinkedHashMap<>();
    private int sequence = -1;
    private boolean behind;

    /** Nothing has been received yet, or a gap was seen: the view is not usable. */
    public boolean needsSnapshot() {
        return sequence < 0 || behind;
    }

    public int sequence() {
        return sequence;
    }

    /** The current view: item type to how many are in range. */
    public Map<ItemKey, Integer> counts() {
        return java.util.Collections.unmodifiableMap(counts);
    }

    public int count(byte[] item) {
        return counts.getOrDefault(new ItemKey(item), 0);
    }

    /**
     * Apply a message.
     *
     * @return true when the view is now current; false when this was a delta that did not
     *         follow on from what we have, in which case a full snapshot must be requested
     */
    public boolean apply(LinkProtocol.Storage storage) {
        if (storage.full()) {
            counts.clear();
            for (LinkProtocol.Entry entry : storage.entries()) {
                if (entry.count() > 0) {
                    counts.put(new ItemKey(entry.item()), entry.count());
                }
            }
            sequence = storage.sequence();
            behind = false;
            return true;
        }
        if (sequence < 0 || storage.sequence() != sequence + 1) {
            behind = true; // a gap: applying this would leave a view that lies about counts
            return false;
        }
        for (LinkProtocol.Entry entry : storage.entries()) {
            ItemKey key = new ItemKey(entry.item());
            if (entry.count() <= 0) {
                counts.remove(key); // a zero in a delta means the type is gone
            } else {
                counts.put(key, entry.count());
            }
        }
        sequence = storage.sequence();
        return true;
    }

    /** Everything that differs between this view and {@code current}, as a delta's entries. */
    public java.util.List<LinkProtocol.Entry> diff(Map<ItemKey, Integer> current) {
        java.util.List<LinkProtocol.Entry> changes = new java.util.ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : current.entrySet()) {
            Integer had = counts.get(e.getKey());
            if (had == null || had.intValue() != e.getValue()) {
                changes.add(new LinkProtocol.Entry(e.getKey().bytes(), e.getValue()));
            }
        }
        for (ItemKey key : counts.keySet()) {
            if (!current.containsKey(key)) {
                changes.add(new LinkProtocol.Entry(key.bytes(), 0));
            }
        }
        return changes;
    }

    /** Adopt {@code current} as the view and take the next sequence number. */
    public int advanceTo(Map<ItemKey, Integer> current) {
        counts.clear();
        counts.putAll(current);
        sequence = sequence + 1;
        behind = false;
        return sequence;
    }
}
