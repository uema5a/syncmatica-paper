package ch.uemasa.syncmatica.comm;

import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.exchange.Exchange;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.RawChannel;
import ch.uemasa.syncmatica.net.SyncByteBuf;
import ch.uemasa.syncmatica.net.SyncmaticaPacket;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Per-connection protocol state for one player: the partner's feature set, active exchanges, and send path. */
public final class ExchangeTarget {

    private final Player player;
    private final SyncmaticaContext context;
    private final List<Exchange> exchanges = new ArrayList<>();
    private FeatureSet featureSet = new FeatureSet(EnumSet.noneOf(Feature.class));

    public ExchangeTarget(Player player, SyncmaticaContext context) {
        this.player = player;
        this.context = context;
    }

    public Player getPlayer() {
        return player;
    }

    /** Stable per-player key used for quota accounting. */
    public String getPersistentName() {
        return player.getUniqueId().toString();
    }

    public FeatureSet getFeatureSet() {
        return featureSet;
    }

    public void setFeatureSet(FeatureSet featureSet) {
        this.featureSet = featureSet;
    }

    public List<Exchange> getExchanges() {
        return exchanges;
    }

    public void send(PacketType type, SyncByteBuf body) {
        send(SyncmaticaPacket.of(type, body));
    }

    public void send(PacketType type) {
        send(SyncmaticaPacket.of(type, new SyncByteBuf()));
    }

    public void send(SyncmaticaPacket packet) {
        // Disconnect cleanup can route a send here for a player who is already leaving;
        // sendPluginMessage would then throw IllegalStateException, so drop it instead.
        if (!player.isOnline()) {
            return;
        }
        if (context.config.isDebugLogging()) {
            context.logger.info("Syncmatica send " + packet.type() + " to " + player.getName());
        }
        try {
            RawChannel.send(player, packet.encode());
        } catch (Throwable t) {
            // Contain a failed send so one bad connection can't abort a broadcast to everyone else.
            context.logger.warning("Failed to send Syncmatica packet to " + player.getName() + ": " + t);
        }
    }
}
