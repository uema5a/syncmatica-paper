package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.Reference;
import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Streams a stored {@code .litematic} to a client that requested it (download flow). Strict ping-pong:
 * one {@code send_litematic} chunk per {@code received_litematic} ack, terminated by {@code finished_litematic}.
 */
public final class UploadExchange extends AbstractExchange {

    private final ServerPlacement placement;
    private final UUID id;
    private final byte[] buffer = new byte[Reference.BUFFER_SIZE];
    private InputStream in;

    public UploadExchange(ExchangeTarget partner, SyncmaticaContext context, ServerPlacement placement) {
        super(partner, context);
        this.placement = placement;
        this.id = placement.getId();
    }

    @Override
    public void init() {
        // Re-check readiness: a missing/mid-finalize file produces a clean cancel rather than a half-open stream.
        if (!context.fileStorage.isFileReady(placement)) {
            context.logger.warning("Upload init aborted for " + id + ": final file not ready.");
            close(true);
            return;
        }
        // Don't stream a corrupted/desynced blob: re-hash on disk before serving (LOCAL_LITEMATIC_DESYNC).
        if (!context.fileStorage.isHashValid(placement)) {
            context.logger.warning("Upload init aborted for " + id + ": on-disk hash mismatch (desync).");
            close(true);
            return;
        }
        try {
            in = Files.newInputStream(context.fileStorage.getFile(placement));
            sendNext();
        } catch (IOException e) {
            context.logger.warning("Upload init failed for " + id + ": " + e.getMessage());
            close(true);
        }
    }

    @Override
    public boolean checkPacket(PacketType type, byte[] body) {
        return !isFinished()
                && (type == PacketType.RECEIVED_LITEMATIC || type == PacketType.CANCEL_LITEMATIC)
                && id.equals(peekUUID(body));
    }

    @Override
    public void handle(PacketType type, SyncByteBuf body) {
        if (type == PacketType.RECEIVED_LITEMATIC) {
            sendNext();
        } else if (type == PacketType.CANCEL_LITEMATIC) {
            close(false);
        }
    }

    private void sendNext() {
        try {
            int read = in.read(buffer);
            if (read == -1) {
                SyncByteBuf buf = new SyncByteBuf();
                buf.writeUUID(id);
                partner.send(PacketType.FINISHED_LITEMATIC, buf);
                succeed();
            } else {
                SyncByteBuf buf = new SyncByteBuf();
                buf.writeUUID(id);
                buf.writeInt(read);
                buf.writeBytes(buffer, 0, read);
                partner.send(PacketType.SEND_LITEMATIC, buf);
            }
        } catch (IOException e) {
            context.logger.warning("Upload read failed for " + id + ": " + e.getMessage());
            close(true);
        }
    }

    @Override
    protected void sendCancelPacket() {
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUUID(id);
        partner.send(PacketType.CANCEL_LITEMATIC, buf);
    }

    @Override
    protected void onClose() {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
