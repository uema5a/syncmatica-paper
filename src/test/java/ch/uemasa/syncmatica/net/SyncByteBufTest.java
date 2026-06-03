package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.Reference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests guarding the bytes that must match the unmodified Syncmatica client.
 */
class SyncByteBufTest {

    @Test
    void varIntKnownVectors() {
        assertArrayEquals(new byte[] {0x00}, write(b -> b.writeVarInt(0)));
        assertArrayEquals(new byte[] {0x01}, write(b -> b.writeVarInt(1)));
        assertArrayEquals(new byte[] {0x7f}, write(b -> b.writeVarInt(127)));
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, write(b -> b.writeVarInt(128)));
        assertArrayEquals(new byte[] {(byte) 0xff, 0x01}, write(b -> b.writeVarInt(255)));
        assertArrayEquals(new byte[] {(byte) 0xdd, (byte) 0xc7, 0x01}, write(b -> b.writeVarInt(25565)));
        assertArrayEquals(
                new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07},
                write(b -> b.writeVarInt(2147483647)));
        assertArrayEquals(
                new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f},
                write(b -> b.writeVarInt(-1)));
    }

    @Test
    void varIntRoundTrip() {
        for (int v : new int[] {0, 1, 127, 128, 255, 25565, Integer.MAX_VALUE, Integer.MIN_VALUE, -1, 1234567}) {
            SyncByteBuf out = new SyncByteBuf();
            out.writeVarInt(v);
            assertEquals(v, new SyncByteBuf(out.toByteArray()).readVarInt());
        }
    }

    @Test
    void fixedIntIsBigEndian() {
        assertArrayEquals(new byte[] {0x00, 0x00, 0x00, 0x01}, write(b -> b.writeInt(1)));
        assertArrayEquals(new byte[] {0x12, 0x34, 0x56, 0x78}, write(b -> b.writeInt(0x12345678)));
        SyncByteBuf out = new SyncByteBuf();
        out.writeInt(-42);
        assertEquals(-42, new SyncByteBuf(out.toByteArray()).readInt());
    }

    @Test
    void utfKnownVectorAndRoundTrip() {
        assertArrayEquals(new byte[] {0x02, 'h', 'i'}, write(b -> b.writeUtf("hi")));
        String s = "minecraft:overworld あ";
        SyncByteBuf out = new SyncByteBuf();
        out.writeUtf(s);
        assertEquals(s, new SyncByteBuf(out.toByteArray()).readUtf());
    }

    @Test
    void uuidRoundTripAndLayout() {
        UUID id = UUID.fromString("4c1b738f-56fa-4011-8273-498c972424ea");
        SyncByteBuf out = new SyncByteBuf();
        out.writeUUID(id);
        byte[] bytes = out.toByteArray();
        assertEquals(16, bytes.length);
        // First 8 bytes = most-significant long, big-endian.
        assertEquals((byte) 0x4c, bytes[0]);
        assertEquals((byte) 0x1b, bytes[1]);
        assertEquals(id, new SyncByteBuf(bytes).readUUID());
    }

    @Test
    void blockPosRoundTripIncludingNegatives() {
        int[][] cases = {{0, 0, 0}, {1, 2, 3}, {-30000000, -2000, 30000000}, {123456, 64, -654321}};
        for (int[] c : cases) {
            SyncByteBuf out = new SyncByteBuf();
            out.writeBlockPos(c[0], c[1], c[2]);
            int[] back = new SyncByteBuf(out.toByteArray()).readBlockPos();
            assertArrayEquals(c, back, "blockpos round-trip " + c[0] + "," + c[1] + "," + c[2]);
        }
    }

    @Test
    void packetFrameRoundTrip() {
        SyncByteBuf body = new SyncByteBuf();
        UUID id = UUID.randomUUID();
        body.writeUUID(id);
        SyncmaticaPacket packet = SyncmaticaPacket.of(PacketType.REQUEST_LITEMATIC, body);

        SyncmaticaPacket decoded = SyncmaticaPacket.decode(packet.encode());
        assertEquals(PacketType.REQUEST_LITEMATIC, decoded.type());
        assertEquals(id, decoded.bodyBuf().readUUID());
    }

    @Test
    void packetTypeIdsMatchWireContract() {
        assertEquals("syncmatica:request_download", PacketType.REQUEST_LITEMATIC.id());
        assertEquals("syncmatica:mesage", PacketType.MESSAGE.id());
        assertEquals(PacketType.MESSAGE, PacketType.fromId("syncmatica:mesage"));
        assertEquals("syncmatica:register_metadata", PacketType.REGISTER_METADATA.id());
    }

    // ---- input-safety guards ----

    @Test
    void readUtfRejectsOversizedDeclaredLengthWithoutAllocating() {
        // Length prefix claims ~2GB but carries no payload; must be rejected before allocating.
        SyncByteBuf out = new SyncByteBuf();
        out.writeVarInt(Integer.MAX_VALUE);
        SyncByteBuf in = new SyncByteBuf(out.toByteArray());
        assertThrows(IllegalStateException.class, in::readUtf);
    }

    @Test
    void readUtfRejectsLengthAbovePacketStringCap() {
        // Just past the protocol string cap.
        SyncByteBuf out = new SyncByteBuf();
        out.writeVarInt(Reference.PACKET_MAX_STRING_SIZE + 1);
        SyncByteBuf in = new SyncByteBuf(out.toByteArray());
        assertThrows(IllegalStateException.class, in::readUtf);
    }

    @Test
    void readUtfRejectsLengthBeyondReadableBytes() {
        // Length within the cap but larger than the bytes actually present.
        SyncByteBuf out = new SyncByteBuf();
        out.writeVarInt(100);
        out.writeBytes(new byte[] {1, 2, 3});
        SyncByteBuf in = new SyncByteBuf(out.toByteArray());
        assertThrows(IllegalStateException.class, in::readUtf);
    }

    @Test
    void readBytesRejectsNegativeLength() {
        SyncByteBuf in = new SyncByteBuf(new byte[] {1, 2, 3, 4});
        assertThrows(IllegalStateException.class, () -> in.readBytes(-1));
    }

    @Test
    void readBytesRejectsLengthBeyondReadable() {
        SyncByteBuf in = new SyncByteBuf(new byte[] {1, 2, 3, 4});
        assertThrows(IllegalStateException.class, () -> in.readBytes(5));
    }

    @Test
    void readBytesRejectsOverflowingLength() {
        // readerIndex + len would overflow; the guard compares against readableBytes instead.
        SyncByteBuf in = new SyncByteBuf(new byte[] {1, 2, 3, 4});
        assertThrows(IllegalStateException.class, () -> in.readBytes(Integer.MAX_VALUE));
    }

    @Test
    void readBytesNormalRoundTripStillWorks() {
        SyncByteBuf out = new SyncByteBuf();
        out.writeBytes(new byte[] {10, 20, 30});
        byte[] back = new SyncByteBuf(out.toByteArray()).readBytes(3);
        assertArrayEquals(new byte[] {10, 20, 30}, back);
    }

    @Test
    void readUtfNormalRoundTripStillWorks() {
        String s = "minecraft:overworld";
        SyncByteBuf out = new SyncByteBuf();
        out.writeUtf(s);
        SyncByteBuf in = new SyncByteBuf(out.toByteArray());
        assertEquals(s, in.readUtf());
        assertTrue(in.readableBytes() == 0, "all bytes consumed");
    }

    private interface Writer {
        void accept(SyncByteBuf buf);
    }

    private static byte[] write(Writer w) {
        SyncByteBuf buf = new SyncByteBuf();
        w.accept(buf);
        return buf.toByteArray();
    }
}
