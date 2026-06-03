package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.Reference;
import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.comm.MessageType;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;
import ch.uemasa.syncmatica.util.Checksum;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Pulls a newly-shared {@code .litematic} from the client (share/upload flow, server is the downloader).
 * Verifies the received bytes against the placement's claimed hash before accepting the share.
 */
public final class DownloadExchange extends AbstractExchange {

    private final ServerPlacement placement;
    private final UUID id;
    private MessageDigest md5;
    private OutputStream out;
    private Path file;
    private long bytesSent;

    public DownloadExchange(ExchangeTarget partner, SyncmaticaContext context, ServerPlacement placement) {
        super(partner, context);
        this.placement = placement;
        this.id = placement.getId();
    }

    @Override
    public void init() {
        try {
            file = context.fileStorage.createDownloadTarget(placement);
            md5 = Checksum.md5();
            out = new DigestOutputStream(Files.newOutputStream(file), md5);
            SyncByteBuf buf = new SyncByteBuf();
            buf.writeUUID(id);
            partner.send(PacketType.REQUEST_LITEMATIC, buf);
        } catch (IOException e) {
            context.logger.warning("Download init failed for " + id + ": " + e.getMessage());
            close(false);
        }
    }

    @Override
    public boolean checkPacket(PacketType type, byte[] body) {
        return !isFinished()
                && (type == PacketType.SEND_LITEMATIC
                || type == PacketType.FINISHED_LITEMATIC
                || type == PacketType.CANCEL_LITEMATIC)
                && id.equals(peekUUID(body));
    }

    @Override
    public void handle(PacketType type, SyncByteBuf body) {
        switch (type) {
            case SEND_LITEMATIC -> {
                body.readUUID();
                int size = body.readInt();
                // The size field is attacker-controlled; reject anything outside a single legal chunk
                // before touching quota or the buffer.
                if (size < 1 || size > Reference.BUFFER_SIZE) {
                    context.logger.warning("Rejected SEND_LITEMATIC with illegal size " + size
                            + " for " + id + ".");
                    close(true);
                    return;
                }
                String uploader = partner.getPersistentName();
                // Check the cumulative transfer against quota before writing; charged once on success in onClose().
                bytesSent += size;
                if (context.quota.isOverQuota(uploader, bytesSent)) {
                    context.comms().sendMessage(partner, MessageType.ERROR,
                            "syncmatica.error.cancelled_transmit_exceed_quota");
                    close(true);
                    return;
                }
                byte[] data = body.readBytes(size);
                try {
                    out.write(data);
                } catch (IOException e) {
                    context.logger.warning("Download write failed for " + id + ": " + e.getMessage());
                    close(true);
                    return;
                }
                SyncByteBuf ack = new SyncByteBuf();
                ack.writeUUID(id);
                partner.send(PacketType.RECEIVED_LITEMATIC, ack);
            }
            case FINISHED_LITEMATIC -> {
                body.readUUID();
                closeStream();
                UUID actual = Checksum.fromDigest(md5.digest());
                if (actual.equals(placement.getHash())) {
                    // Promote the .part to the final blob only on a hash match, so the final blob is never partial.
                    try {
                        context.fileStorage.finalizeDownload(placement);
                    } catch (IOException e) {
                        context.logger.warning("Download finalize failed for " + id + ": " + e.getMessage());
                        close(false);
                        return;
                    }
                    succeed();
                } else {
                    context.logger.warning("Hash mismatch on shared litematic " + id
                            + " (expected " + placement.getHash() + ", got " + actual + ").");
                    close(false);
                }
            }
            case CANCEL_LITEMATIC -> close(false);
            default -> {
            }
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
        closeStream();
        if (isSuccessful()) {
            // Charge the whole transfer once, only on success (matches upstream); failures cost nothing.
            context.quota.addProgress(partner.getPersistentName(), bytesSent);
            context.comms().onShareComplete(placement);
        } else {
            // Remove only this placement's .part; a completed identical blob (same hash) at the final path stays.
            try {
                context.fileStorage.deleteDownloadTarget(placement);
            } catch (IOException ignored) {
            }
            context.comms().onShareFailed(placement, partner);
        }
    }

    private void closeStream() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }
}
