package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.data.PlayerIdentifier;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.net.MetadataCodec;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;

import java.util.UUID;

/**
 * Grants a client an exclusive lock to modify a placement, receives the new position on
 * {@code modify_finish}, persists it, and broadcasts the change to everyone.
 */
public final class ModifyExchangeServer extends AbstractExchange {

    private final UUID placementId;
    private ServerPlacement placement;

    public ModifyExchangeServer(ExchangeTarget partner, SyncmaticaContext context, UUID placementId) {
        super(partner, context);
        this.placementId = placementId;
    }

    @Override
    public void init() {
        placement = context.syncManager.get(placementId);
        if (placement == null
                || context.comms().getModifier(placementId) != null
                || !context.comms().checkPermission(partner, "syncmatica.modify")) {
            close(true);
            return;
        }
        context.comms().registerModifier(placementId, this);
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUUID(placementId);
        partner.send(PacketType.MODIFY_REQUEST_ACCEPT, buf);
    }

    @Override
    public boolean checkPacket(PacketType type, byte[] body) {
        return !isFinished() && type == PacketType.MODIFY_FINISH && placementId.equals(peekUUID(body));
    }

    @Override
    public void handle(PacketType type, SyncByteBuf body) {
        if (type == PacketType.MODIFY_FINISH) {
            body.readUUID();
            MetadataCodec.readPositionData(body, placement, context.featureSet);
            PlayerIdentifier mod = context.players.createOrGet(
                    partner.getPlayer().getUniqueId(), partner.getPlayer().getName());
            placement.setLastModifiedBy(mod);
            context.syncManager.save();
            succeed();
        }
    }

    @Override
    protected void sendCancelPacket() {
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUUID(placementId);
        partner.send(PacketType.MODIFY_REQUEST_DENY, buf);
    }

    @Override
    protected void onClose() {
        // Only clear the registry if we are still the registered modifier: a remove that cancelled us
        // may have already replaced/removed the entry, and we must not clobber a newer one.
        if (context.comms().getModifier(placementId) == this) {
            context.comms().releaseModifyLock(placementId);
        }
        if (isSuccessful()) {
            context.comms().broadcastModify(placement);
        }
    }
}
