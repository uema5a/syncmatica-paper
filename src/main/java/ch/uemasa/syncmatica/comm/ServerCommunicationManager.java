package ch.uemasa.syncmatica.comm;

import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.exchange.AbstractExchange;
import ch.uemasa.syncmatica.comm.exchange.DownloadExchange;
import ch.uemasa.syncmatica.comm.exchange.Exchange;
import ch.uemasa.syncmatica.comm.exchange.ModifyExchangeServer;
import ch.uemasa.syncmatica.comm.exchange.UploadExchange;
import ch.uemasa.syncmatica.comm.exchange.VersionHandshakeServer;
import ch.uemasa.syncmatica.data.PlayerIdentifier;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.net.MetadataCodec;
import ch.uemasa.syncmatica.net.PacketType;
import ch.uemasa.syncmatica.net.SyncByteBuf;
import ch.uemasa.syncmatica.net.SyncmaticaPacket;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side protocol hub: tracks connections, drives the handshake, routes incoming packets to active
 * exchanges or top-level handlers, and broadcasts placement changes. All methods run on the main thread.
 */
public final class ServerCommunicationManager {

    // Upper bound on concurrent exchanges per client. Each in-flight upload holds an open stream and
    // buffer, so an uncapped client could open many and exhaust file handles until the stale sweep.
    private static final int MAX_EXCHANGES_PER_TARGET = 16;

    private final SyncmaticaContext context;

    private final Map<UUID, ExchangeTarget> targets = new HashMap<>();
    private final Set<ExchangeTarget> broadcastTargets = new HashSet<>();
    // Active modifiers keyed by placement id; holding the exchange (not just a flag) lets a remove cancel
    // the in-flight editor so a late MODIFY_FINISH can't re-broadcast a removed placement.
    private final Map<UUID, ModifyExchangeServer> modifiers = new HashMap<>();

    // In-flight shares keyed by content hash: duplicate shares of a hash already downloading are queued
    // here rather than re-transferred, then completed or cancelled as a batch when the download finishes.
    private final Map<UUID, List<PendingShare>> downloading = new HashMap<>();

    private record PendingShare(ServerPlacement placement, ExchangeTarget target) {
    }

    public ServerCommunicationManager(SyncmaticaContext context) {
        this.context = context;
    }

    /** Called when a client registers the channel; starts the handshake. */
    public void onChannelRegistered(Player player) {
        if (targets.containsKey(player.getUniqueId())) {
            return;
        }
        // Stamp the connecting player's real name authoritatively (remote metadata never overwrites it).
        context.players.refresh(player.getUniqueId(), player.getName());
        ExchangeTarget target = new ExchangeTarget(player, context);
        targets.put(player.getUniqueId(), target);
        startExchange(target, new VersionHandshakeServer(target, context));
    }

    public void onPlayerQuit(Player player) {
        ExchangeTarget target = targets.remove(player.getUniqueId());
        if (target == null) {
            return;
        }
        for (Exchange e : new ArrayList<>(target.getExchanges())) {
            e.close(false);
        }
        target.getExchanges().clear();
        broadcastTargets.remove(target);
    }

    /** @return true if the exchange was started; false if the per-target cap refused it. */
    public boolean startExchange(ExchangeTarget target, Exchange exchange) {
        if (target.getExchanges().size() >= MAX_EXCHANGES_PER_TARGET) {
            context.logger.warning("Refusing a new Syncmatica exchange for " + target.getPlayer().getName()
                    + ": too many concurrent exchanges (" + target.getExchanges().size() + ").");
            return false;
        }
        target.getExchanges().add(exchange);
        exchange.init();
        if (exchange.isFinished()) {
            target.getExchanges().remove(exchange);
        }
        return true;
    }

    public void onPacket(Player player, byte[] raw) {
        ExchangeTarget target = targets.get(player.getUniqueId());
        if (target == null) {
            // A packet with no active handshake target: normally just a pre-handshake/post-quit race.
            if (context.config.isDebugLogging()) {
                context.logger.info("Syncmatica recv from " + player.getName()
                        + " with no active target (pre-handshake or post-quit).");
            }
            return;
        }
        // The exchange currently being driven, so a throw mid-handle closes only that exchange.
        Exchange handling = null;
        try {
            SyncmaticaPacket packet = SyncmaticaPacket.decode(raw);
            if (packet == null) {
                return;
            }
            if (context.config.isDebugLogging()) {
                context.logger.info("Syncmatica recv " + packet.type() + " from " + player.getName());
            }
            for (Exchange e : new ArrayList<>(target.getExchanges())) {
                if (e.checkPacket(packet.type(), packet.body())) {
                    handling = e;
                    if (e instanceof AbstractExchange ae) {
                        ae.touch();
                    }
                    e.handle(packet.type(), packet.bodyBuf());
                    if (e.isFinished()) {
                        target.getExchanges().remove(e);
                    }
                    return;
                }
            }
            handleTopLevel(target, packet);
        } catch (Throwable t) {
            // Catch Throwable: a malformed packet (or OOM/StackOverflow from parsing) must never
            // propagate to the main thread. Drop the packet and tear down any in-flight exchange.
            context.logger.warning("Dropping malformed Syncmatica packet from "
                    + player.getName() + ": " + t);
            if (handling != null) {
                try {
                    handling.close(false);
                } catch (Throwable ignored) {
                }
                target.getExchanges().remove(handling);
            }
        }
    }

    private void handleTopLevel(ExchangeTarget target, SyncmaticaPacket packet) {
        SyncByteBuf buf = packet.bodyBuf();
        switch (packet.type()) {
            // FEATURE / FEATURE_REQUEST are answered only inside the handshake exchange; ignoring them
            // top-level prevents mid-session feature re-negotiation/downgrade (matches upstream).
            case REQUEST_LITEMATIC -> handleRequestDownload(target, buf);
            case REGISTER_METADATA -> handleRegisterMetadata(target, buf);
            case REMOVE_SYNCMATIC -> handleRemove(target, buf);
            case MODIFY_REQUEST -> startExchange(target, new ModifyExchangeServer(target, context, buf.readUUID()));
            default -> {
            }
        }
    }

    private void handleRequestDownload(ExchangeTarget target, SyncByteBuf buf) {
        UUID id = buf.readUUID();
        if (!checkPermission(target, "syncmatica.download")) {
            return;
        }
        ServerPlacement placement = context.syncManager.get(id);
        if (placement == null || !context.fileStorage.isFileReady(placement)) {
            return;
        }
        startExchange(target, new UploadExchange(target, context, placement));
    }

    private void handleRegisterMetadata(ExchangeTarget target, SyncByteBuf buf) {
        ServerPlacement placement = MetadataCodec.readMetaData(buf, context.featureSet, context.players);
        if (context.syncManager.contains(placement.getId())) {
            cancelShare(target, placement.getId());
            return;
        }
        if (!checkPermission(target, "syncmatica.share")) {
            cancelShare(target, placement.getId());
            return;
        }
        PlayerIdentifier sharer = context.players.createOrGet(
                target.getPlayer().getUniqueId(), target.getPlayer().getName());
        // Stamp the connecting player as owner unless they already are it; a client cannot forge
        // someone else's owner (upstream behavior).
        if (!placement.getOwner().equals(sharer)) {
            placement.setOwner(sharer);
            placement.setLastModifiedBy(sharer);
        }
        if (context.fileStorage.isFileReady(placement)) {
            // Identical content already stored; no transfer needed.
            onShareComplete(placement);
            return;
        }
        UUID hash = placement.getHash();
        List<PendingShare> queue = downloading.get(hash);
        if (queue != null) {
            // Same content already being pulled; queue this placement for the active download to satisfy.
            queue.add(new PendingShare(placement, target));
            return;
        }
        downloading.put(hash, new ArrayList<>());
        if (!startExchange(target, new DownloadExchange(target, context, placement))) {
            // Refused by the cap: drop the placeholder we just added so the hash isn't stuck "downloading".
            downloading.remove(hash);
        }
    }

    private void handleRemove(ExchangeTarget target, SyncByteBuf buf) {
        UUID id = buf.readUUID();
        if (!checkPermission(target, "syncmatica.remove")) {
            return;
        }
        // Cancel any in-flight editor BEFORE removing: close(true) emits MODIFY_REQUEST_DENY to the editor
        // and drops the exchange from its partner so a late MODIFY_FINISH can't re-broadcast the removed
        // placement. close() clears the modifier via onClose().
        ModifyExchangeServer m = getModifier(id);
        if (m != null) {
            m.close(true);
            m.getPartner().getExchanges().remove(m);
        }
        ServerPlacement removed = context.syncManager.removePlacement(id);
        if (removed != null) {
            try {
                context.fileStorage.delete(removed);
            } catch (Exception e) {
                context.logger.warning("Failed to delete file for " + id + ": " + e.getMessage());
            }
            broadcastRemove(id);
        }
    }

    public void markReady(ExchangeTarget target) {
        broadcastTargets.add(target);
    }

    public void onShareComplete(ServerPlacement placement) {
        // Guard against an id that got registered meanwhile (e.g. a concurrent share of the same id):
        // skip its add+broadcast, but still process the queued duplicates below.
        if (!context.syncManager.contains(placement.getId())) {
            context.syncManager.addPlacement(placement);
            broadcastMetaData(placement);
        }
        // Blob is present now, so every duplicate queued behind this hash can be registered and broadcast.
        List<PendingShare> queued = downloading.remove(placement.getHash());
        if (queued != null) {
            for (PendingShare pending : queued) {
                if (context.syncManager.contains(pending.placement().getId())) {
                    cancelShare(pending.target(), pending.placement().getId());
                    continue;
                }
                context.syncManager.addPlacement(pending.placement());
                broadcastMetaData(pending.placement());
            }
        }
    }

    public void onShareFailed(ServerPlacement placement, ExchangeTarget sharer) {
        cancelShare(sharer, placement.getId());
        // Transfer for this hash failed; nothing landed on disk, so cancel every queued duplicate too.
        List<PendingShare> queued = downloading.remove(placement.getHash());
        if (queued != null) {
            for (PendingShare pending : queued) {
                cancelShare(pending.target(), pending.placement().getId());
            }
        }
    }

    /**
     * Closes any exchange idle longer than {@code timeoutMillis}, so a client that goes silent mid-transfer
     * cannot pin a modify lock or leak a {@code .part} file forever.
     */
    public void sweepStaleExchanges(long timeoutMillis) {
        for (ExchangeTarget target : new ArrayList<>(targets.values())) {
            for (Exchange e : new ArrayList<>(target.getExchanges())) {
                if (e instanceof AbstractExchange ae && ae.isStale(timeoutMillis)) {
                    context.logger.warning("Closing stale Syncmatica exchange for "
                            + target.getPlayer().getName() + " after " + timeoutMillis + "ms idle.");
                    e.close(true);
                    target.getExchanges().remove(e);
                }
            }
        }
    }

    public void registerModifier(UUID id, ModifyExchangeServer ex) {
        modifiers.put(id, ex);
    }

    public ModifyExchangeServer getModifier(UUID id) {
        return modifiers.get(id);
    }

    /** Kept public: SyncmaticaCommand releases the lock when force-clearing a stuck modify. */
    public void releaseModifyLock(UUID id) {
        modifiers.remove(id);
    }

    /** Registers a placement and pushes it to all connected clients. */
    public void publishPlacement(ServerPlacement placement) {
        context.syncManager.addPlacement(placement);
        broadcastMetaData(placement);
    }

    public void broadcastMetaData(ServerPlacement placement) {
        for (ExchangeTarget target : broadcastTargets) {
            SyncByteBuf buf = new SyncByteBuf();
            MetadataCodec.writeMetaData(buf, placement, target.getFeatureSet());
            target.send(PacketType.REGISTER_METADATA, buf);
        }
    }

    public void broadcastRemove(UUID id) {
        for (ExchangeTarget target : broadcastTargets) {
            SyncByteBuf buf = new SyncByteBuf();
            buf.writeUUID(id);
            target.send(PacketType.REMOVE_SYNCMATIC, buf);
        }
    }

    public void broadcastModify(ServerPlacement placement) {
        for (ExchangeTarget target : broadcastTargets) {
            FeatureSet fs = target.getFeatureSet();
            if (fs.hasFeature(Feature.MODIFY)) {
                SyncByteBuf buf = new SyncByteBuf();
                buf.writeUUID(placement.getId());
                MetadataCodec.writePositionData(buf, placement, fs);
                if (fs.hasFeature(Feature.CORE_EX)) {
                    PlayerIdentifier last = placement.getLastModifiedBy();
                    buf.writeUUID(last.uuid);
                    buf.writeUtf(last.getName());
                }
                target.send(PacketType.MODIFY, buf);
            } else {
                // Legacy clients without MODIFY: remove and re-add.
                SyncByteBuf rb = new SyncByteBuf();
                rb.writeUUID(placement.getId());
                target.send(PacketType.REMOVE_SYNCMATIC, rb);
                SyncByteBuf mb = new SyncByteBuf();
                MetadataCodec.writeMetaData(mb, placement, fs);
                target.send(PacketType.REGISTER_METADATA, mb);
            }
        }
    }

    public void sendMessage(ExchangeTarget target, MessageType type, String key) {
        // Upstream only sends the MESSAGE wire packet when the partner negotiated Feature.MESSAGE;
        // otherwise it falls back to a plain chat line (Player#sendMessage is safe if offline).
        if (target.getFeatureSet().hasFeature(Feature.MESSAGE)) {
            SyncByteBuf buf = new SyncByteBuf();
            buf.writeUtf(type.name());
            buf.writeUtf(key);
            target.send(PacketType.MESSAGE, buf);
        } else {
            target.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text("Syncmatica " + type.name() + " " + key));
        }
    }

    private void cancelShare(ExchangeTarget target, UUID id) {
        SyncByteBuf buf = new SyncByteBuf();
        buf.writeUUID(id);
        target.send(PacketType.CANCEL_SHARE, buf);
    }

    /** Open by default; only enforces the permission node when {@code permissions.enforce} is true. */
    public boolean checkPermission(ExchangeTarget target, String node) {
        if (!context.config.isPermissionsEnforced()) {
            return true;
        }
        return target.getPlayer().hasPermission(node);
    }
}
