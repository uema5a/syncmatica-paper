package ch.uemasa.syncmatica.util;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads the display name and two version numbers from a {@code .litematic} (gzipped NBT), skipping
 * block-data arrays. Used by {@code /syncmatica load} for admin-imported files.
 */
public final class LitematicPeek {

    /** NBT nesting depth cap (stack-overflow guard for hostile files). */
    private static final int MAX_DEPTH = 512;

    /** TAG_List element-count cap, checked before the alloc loop. */
    private static final int MAX_LIST_LENGTH = 1 << 20;

    /** Decompressed-byte cap to abort gzip bombs before they exhaust the heap. */
    private static final long MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024;

    private LitematicPeek() {
    }

    public record Info(String name, int litematicVersion, int dataVersion) {
    }

    public static Info read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new CountingInputStream(
                        new GZIPInputStream(Files.newInputStream(file)), MAX_DECOMPRESSED_BYTES)))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) {
                throw new IOException("Not an NBT compound root");
            }
            in.readUTF(); // root name (usually empty)
            Map<String, Object> root = readCompound(in, 0);

            String name = null;
            if (root.get("Metadata") instanceof Map<?, ?> meta && meta.get("Name") instanceof String s) {
                name = s;
            }
            int litematicVersion = root.get("Version") instanceof Integer v ? v : -1;
            int dataVersion = root.get("MinecraftDataVersion") instanceof Integer v ? v : -1;
            return new Info(name, litematicVersion, dataVersion);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep (> " + MAX_DEPTH + ")");
        }
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) { // TAG_End
                break;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth));
        }
        return map;
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep (> " + MAX_DEPTH + ")");
        }
        switch (type) {
            case 1: // byte
                return (int) in.readByte();
            case 2: // short
                return (int) in.readShort();
            case 3: // int
                return in.readInt();
            case 4: // long
                return in.readLong();
            case 5: // float
                in.readFloat();
                return null;
            case 6: // double
                in.readDouble();
                return null;
            case 7: { // byte array
                int len = in.readInt();
                skipFully(in, (long) len);
                return null;
            }
            case 8: // string
                return in.readUTF();
            case 9: { // list
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > MAX_LIST_LENGTH) {
                    throw new IOException("NBT list length out of range: " + len);
                }
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType, depth + 1));
                }
                return list;
            }
            case 10: // compound
                return readCompound(in, depth + 1);
            case 11: { // int array
                int len = in.readInt();
                skipFully(in, 4L * len);
                return null;
            }
            case 12: { // long array
                int len = in.readInt();
                skipFully(in, 8L * len);
                return null;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /** Caps total bytes read (including via {@link #skip(long)}) to bound an untrusted GZIP stream. */
    private static final class CountingInputStream extends FilterInputStream {

        private final long maxBytes;
        private long count;

        CountingInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > maxBytes) {
                throw new IOException("Decompressed data exceeds " + maxBytes + " bytes (possible zip bomb)");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                add(skipped);
            }
            return skipped;
        }
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF while skipping");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
