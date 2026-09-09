package com.dierks.craftbridge.jei;

import java.io.ByteArrayOutputStream;

/** Minecraft-protocol VarInt (LEB128, at most 5 bytes) reader/writer over a byte array. */
public final class VarInts {

    private VarInts() {
    }

    /** Sequential reader with bounds checks; every failure is an {@link IllegalArgumentException}. */
    public static final class Reader {
        private final byte[] data;
        private int pos;

        public Reader(byte[] data) {
            this.data = data;
        }

        public int remaining() {
            return data.length - pos;
        }

        public boolean hasRemaining() {
            return pos < data.length;
        }

        public int readVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IllegalArgumentException("VarInt runs past end of payload at byte " + pos);
                }
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 35) {
                    throw new IllegalArgumentException("VarInt too big at byte " + pos);
                }
            }
        }

        /** A length-prefixed UTF-8 string. */
        public String readString() {
            int length = readVarInt();
            if (length < 0 || length > remaining()) {
                throw new IllegalArgumentException("string length " + length + " runs past end of payload");
            }
            String value = new String(data, pos, length, java.nio.charset.StandardCharsets.UTF_8);
            pos += length;
            return value;
        }

        /** A length-prefixed blob — an item stack encoded by the game's own codec, say. */
        public byte[] readBytes() {
            int length = readVarInt();
            if (length < 0 || length > remaining()) {
                throw new IllegalArgumentException("blob length " + length + " runs past end of payload");
            }
            byte[] value = java.util.Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return value;
        }

        public boolean readBoolean() {
            if (pos >= data.length) {
                throw new IllegalArgumentException("boolean runs past end of payload at byte " + pos);
            }
            return data[pos++] != 0;
        }
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
            return this;
        }

        public Writer writeBoolean(boolean value) {
            out.write(value ? 1 : 0);
            return this;
        }

        public Writer writeString(String value) {
            byte[] utf8 = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarInt(utf8.length);
            out.writeBytes(utf8);
            return this;
        }

        public Writer writeBytes(byte[] value) {
            byte[] safe = value == null ? new byte[0] : value;
            writeVarInt(safe.length);
            out.writeBytes(safe);
            return this;
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
