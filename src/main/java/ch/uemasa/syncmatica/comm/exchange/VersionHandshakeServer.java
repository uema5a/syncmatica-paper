package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.Reference;
import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.comm.FeatureSet;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.net.MetadataCodec;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;

/**
 * Server side of the join handshake (version exchange, then feature negotiation). On success the partner
 * is added to the broadcast set so it receives live updates.
 */
public final class VersionHandshakeServer extends AbstractExchange {

    public VersionHandshakeServer(ExchangeTarget partner, SyncmaticaContext context) {
        super(partner, context);
    }

    @Override
    public void init() {
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUtf(context.config.getProtocolVersion());
        partner.send(PacketType.REGISTER_VERSION, buf);
    }

    @Override
    public boolean checkPacket(PacketType type, byte[] body) {
        return !isFinished()
                && (type == PacketType.FEATURE_REQUEST
                || type == PacketType.REGISTER_VERSION
                || type == PacketType.FEATURE);
    }

    @Override
    public void handle(PacketType type, SyncByteBuf body) {
        switch (type) {
            case FEATURE_REQUEST -> sendFeatures();
            case REGISTER_VERSION -> {
                String version = body.readUtf();
                if (Reference.INCOMPATIBLE_VERSION.equals(version)) {
                    context.logger.info("Denying Syncmatica handshake for "
                            + partner.getPlayer().getName() + " (incompatible client version).");
                    close(false);
                } else {
                    partner.send(PacketType.FEATURE_REQUEST);
                }
            }
            case FEATURE -> {
                partner.setFeatureSet(FeatureSet.fromString(body.readUtf()));
                sendConfirmUser();
                succeed();
            }
            default -> {
            }
        }
    }

    private void sendFeatures() {
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUtf(context.featureSet.toString());
        partner.send(PacketType.FEATURE, buf);
    }

    private void sendConfirmUser() {
        SyncByteBuf buf = new SyncByteBuf();
        var all = context.syncManager.getAll();
        buf.writeInt(all.size());
        for (ServerPlacement p : all) {
            MetadataCodec.writeMetaData(buf, p, partner.getFeatureSet());
        }
        partner.send(PacketType.CONFIRM_USER, buf);
    }

    @Override
    protected void onClose() {
        if (isSuccessful()) {
            context.comms().markReady(partner);
        }
    }
}
