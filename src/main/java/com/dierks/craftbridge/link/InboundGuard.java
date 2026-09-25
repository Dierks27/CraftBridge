package com.dierks.craftbridge.link;

import com.dierks.craftbridge.jei.VarInts;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-player limits on what a client may send over the link, and how to read a payload that
 * would not decode.
 *
 * <p>Every link message costs the server real work on the main thread: a hello builds and sends
 * the whole item catalog, a resync re-scans every container in range, a pull or transfer moves
 * items. The stock client sends a handful of these a second at most, so a budget far above
 * that costs an honest player nothing and stops a modified one from turning the link into a
 * lag machine. Messages over budget are dropped, not answered: answering is the cost being
 * avoided. The client already copes with an unanswered request (it times out) and with an
 * unanswered hello (it retries), so a drop never wedges it.
 *
 * <p>Bukkit-free, with the clock passed in, so the budgets are unit tests.
 */
final class InboundGuard {

    /** A token bucket per message kind: {@code burst} at once, refilled at {@code perSecond}. */
    enum Kind {
        /** Builds and sends the catalog: once a second is plenty (the client retries every few). */
        HELLO(1, 1),
        /** A full re-scan and snapshot: once a second. */
        RESYNC(1, 1),
        /** Toggles phantom slots on or off: a few, then one a second. */
        STORAGE_ACK(4, 1),
        /** A click in the storage panel: fast clicking stays well inside this. */
        PULL_REQUEST(20, 10),
        /** A JEI [+]: a burst of shift-clicks stays inside this. */
        TRANSFER_REQUEST(10, 4);

        final double burst;
        final double perSecond;

        Kind(double burst, double perSecond) {
            this.burst = burst;
            this.perSecond = perSecond;
        }

        /** The kind for an incoming channel, or null for one this guard does not limit. */
        static Kind of(String channel) {
            return switch (channel) {
                case LinkProtocol.CHANNEL_HELLO -> HELLO;
                case LinkProtocol.CHANNEL_RESYNC -> RESYNC;
                case LinkProtocol.CHANNEL_STORAGE_ACK -> STORAGE_ACK;
                case LinkProtocol.CHANNEL_PULL_REQUEST -> PULL_REQUEST;
                case LinkProtocol.CHANNEL_TRANSFER_REQUEST -> TRANSFER_REQUEST;
                default -> null;
            };
        }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final Map<Kind, Bucket> buckets = new EnumMap<>(Kind.class);
    private long lastDropReport = Long.MIN_VALUE;

    /** May a message on {@code channel} arriving at {@code nowNanos} be handled? Spends a token if so. */
    boolean allow(String channel, long nowNanos) {
        Kind kind = Kind.of(channel);
        if (kind == null) {
            return true;
        }
        Bucket bucket = buckets.get(kind);
        if (bucket == null) {
            bucket = new Bucket(kind.burst, nowNanos);
            buckets.put(kind, bucket);
        } else {
            double elapsed = Math.max(0L, nowNanos - bucket.lastNanos) / (double) NANOS_PER_SECOND;
            bucket.tokens = Math.min(kind.burst, bucket.tokens + elapsed * kind.perSecond);
            bucket.lastNanos = nowNanos;
        }
        if (bucket.tokens < 1) {
            return false;
        }
        bucket.tokens -= 1;
        return true;
    }

    /** Whether a drop at {@code nowNanos} should be logged: at most once a second per player. */
    boolean shouldReportDrop(long nowNanos) {
        if (lastDropReport != Long.MIN_VALUE && nowNanos - lastDropReport < NANOS_PER_SECOND) {
            return false;
        }
        lastDropReport = nowNanos;
        return true;
    }

    /**
     * Did this payload fail because it was written for another protocol version, rather than
     * because it is truncated or garbage? Both surface as an IllegalArgumentException from
     * {@link LinkProtocol}; only the first is worth telling the player about (and unlinking
     * them for). A payload too short to hold even the version is garbage, not a mismatch.
     */
    static boolean isVersionMismatch(byte[] payload) {
        try {
            return new VarInts.Reader(payload).readVarInt() != LinkProtocol.VERSION;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
