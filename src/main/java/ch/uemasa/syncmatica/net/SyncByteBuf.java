package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.Reference;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Dependency-free re-implementation of the {@code FriendlyByteBuf} wire encodings the Syncmatica
 * protocol uses. Construct empty to write, or from a {@code byte[]} to read.
 */
public final class SyncByteBuf {

    private byte[] data;
    private int writerIndex;
    private int readerIndex;

    /** New empty buffer for writing. */
    public SyncByteBuf() {
        this.data = new byte[64];
    }

    /** Wrap an existing array for reading. */
    public SyncByteBuf(byte[] src) {
        this.data = src;
        this.writerIndex = src.length;
    }

    // ---- capacity / state ----

    private void ensure(int extra) {
        int required = writerIndex + extra;
        if (required > data.length) {
            int newCap = Math.max(data.length * 2, required);
            data = Arrays.copyOf(data, newCap);
        }
    }

    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, writerIndex);
    }

    // ---- raw bytes ----

    public void writeByte(int b) {
        ensure(1);
        data[writerIndex++] = (byte) b;
    }

    public byte readByte() {
        if (readerIndex >= writerIndex) {
            throw new IndexOutOfBoundsException("readByte past end");
        }
        return data[readerIndex++];
    }

    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    public void writeBytes(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, data, writerIndex, len);
        writerIndex += len;
    }

    public byte[] readBytes(int len) {
        // Bound against readableBytes (not readerIndex + len, which can overflow) before allocating.
        if (len < 0 || len > readableBytes()) {
            throw new IllegalStateException("readBytes length out of range: " + len);
        }
        byte[] out = Arrays.copyOfRange(data, readerIndex, readerIndex + len);
        readerIndex += len;
        return out;
    }

    // ---- fixed-width big-endian ----

    public void writeInt(int v) {
        ensure(4);
        data[writerIndex++] = (byte) (v >>> 24);
        data[writerIndex++] = (byte) (v >>> 16);
        data[writerIndex++] = (byte) (v >>> 8);
        data[writerIndex++] = (byte) v;
    }

    public int readInt() {
        return ((readByte() & 0xFF) << 24)
                | ((readByte() & 0xFF) << 16)
                | ((readByte() & 0xFF) << 8)
                | (readByte() & 0xFF);
    }

    public void writeLong(long v) {
        ensure(8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            data[writerIndex++] = (byte) (v >>> shift);
        }
    }

    public long readLong() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (readByte() & 0xFF);
        }
        return v;
    }

    // ---- VarInt (unsigned LEB128) ----

    public void writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        writeByte(value);
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IllegalStateException("VarInt too big");
            }
        } while (true);
        return value;
    }

    // ---- UTF string ----

    public void writeUtf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public String readUtf() {
        int len = readVarInt();
        // Reject an oversized declared length before readBytes allocates.
        if (len < 0 || len > Reference.PACKET_MAX_STRING_SIZE || len > readableBytes()) {
            throw new IllegalStateException("readUtf length out of range: " + len);
        }
        byte[] bytes = readBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---- UUID ----

    public void writeUUID(UUID id) {
        writeLong(id.getMostSignificantBits());
        writeLong(id.getLeastSignificantBits());
    }

    public UUID readUUID() {
        long msb = readLong();
        long lsb = readLong();
        return new UUID(msb, lsb);
    }

    // BlockPos packed long: ((x&0x3FFFFFF)<<38)|((z&0x3FFFFFF)<<12)|(y&0xFFF)

    public static long packBlockPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0xFFF));
    }

    public void writeBlockPos(int x, int y, int z) {
        writeLong(packBlockPos(x, y, z));
    }

    /** @return {@code [x, y, z]} */
    public int[] readBlockPos() {
        long val = readLong();
        int x = (int) (val >> 38);
        int y = (int) (val << 52 >> 52);
        int z = (int) (val << 26 >> 38);
        return new int[] {x, y, z};
    }

    // ---- Identifier ("namespace:path") ----

    public void writeIdentifier(String id) {
        writeUtf(id);
    }

    public String readIdentifier() {
        return readUtf();
    }
}
