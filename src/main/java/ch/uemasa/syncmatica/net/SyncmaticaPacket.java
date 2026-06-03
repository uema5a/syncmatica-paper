package ch.uemasa.syncmatica.net;

/**
 * One Syncmatica message: a {@link PacketType} discriminator plus its raw body bytes. On the wire the
 * frame is the type Identifier followed by the body, which runs to the end of the buffer.
 */
public record SyncmaticaPacket(PacketType type, byte[] body) {

    /** Encode this packet into the raw {@code byte[]} sent over the plugin-message channel. */
    public byte[] encode() {
        SyncByteBuf out = new SyncByteBuf();
        out.writeIdentifier(type.id());
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Build a packet from a type and an already-written body buffer. */
    public static SyncmaticaPacket of(PacketType type, SyncByteBuf body) {
        return new SyncmaticaPacket(type, body.toByteArray());
    }

    /**
     * Decode a raw channel payload into a packet.
     *
     * @return the decoded packet, or {@code null} if the inner type id is not a known {@link PacketType}.
     */
    public static SyncmaticaPacket decode(byte[] raw) {
        SyncByteBuf in = new SyncByteBuf(raw);
        String id = in.readIdentifier();
        PacketType type = PacketType.fromId(id);
        if (type == null) {
            return null;
        }
        byte[] body = in.readBytes(in.readableBytes());
        return new SyncmaticaPacket(type, body);
    }

    /** A fresh reader positioned at the start of this packet's body. */
    public SyncByteBuf bodyBuf() {
        return new SyncByteBuf(body);
    }
}
