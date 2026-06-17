package ch.uemasa.syncmatica;

import ch.uemasa.syncmatica.command.SyncmaticaCommand;
import ch.uemasa.syncmatica.comm.ServerCommunicationManager;
import ch.uemasa.syncmatica.config.PluginConfig;
import ch.uemasa.syncmatica.config.QuotaService;
import ch.uemasa.syncmatica.data.FileStorage;
import ch.uemasa.syncmatica.data.PlayerIdentifierProvider;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.data.SyncmaticManager;
import ch.uemasa.syncmatica.net.ChannelMessageListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plugin entry point. Wires the server-side Syncmatica services and registers the channel,
 * connection listener, and {@code /syncmatica} command.
 */
public final class SyncmaticaPlugin extends JavaPlugin {

    private static final long EXCHANGE_TIMEOUT_MILLIS = 60_000L;
    private static final long SWEEP_PERIOD_SECONDS = 20L;

    private SyncmaticaContext context;
    private ScheduledExecutorService protocolExecutor;
    private ScheduledFuture<?> staleSweepTask;

    @Override
    public void onEnable() {
        // Without the reflective NMS transport the plugin can receive but never reply, so clients would
        // hang mid-handshake. Disable rather than run with a non-functional send path.
        if (!ch.uemasa.syncmatica.net.RawChannel.isAvailable()) {
            getLogger().severe("Syncmatica NMS transport did not resolve — disabling the plugin. "
                    + "This build likely does not match the server's Minecraft version.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        PluginConfig config = PluginConfig.load(getConfig());

        Path dataFolder = getDataFolder().toPath();
        Path blobFolder = dataFolder.resolve("syncmatics");
        Path placementsFile = dataFolder.resolve("placements.json");

        PlayerIdentifierProvider players = new PlayerIdentifierProvider();
        SyncmaticManager syncManager = new SyncmaticManager(placementsFile, players, getLogger());
        FileStorage fileStorage = new FileStorage(blobFolder);
        QuotaService quota = new QuotaService(config.isQuotaEnabled(), config.getQuotaLimit());

        // A single thread owns all protocol state. Both Paper and Folia deliver the plugin's events,
        // commands, and plugin messages on threads we don't control (several region threads at once on
        // Folia), so confining every state mutation to this one thread keeps the existing single-threaded
        // model correct without sprinkling locks through the protocol code.
        protocolExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Syncmatica-Protocol");
            t.setDaemon(true);
            return t;
        });

        context = new SyncmaticaContext(this, config, players, syncManager, fileStorage, quota, protocolExecutor);
        ServerCommunicationManager comms = new ServerCommunicationManager(context);
        context.setComms(comms);

        syncManager.load();

        // Warn about persisted placements whose backing blob is missing; they can never be downloaded.
        for (ServerPlacement p : syncManager.getAll()) {
            if (!fileStorage.isFileReady(p)) {
                getLogger().warning("Loaded placement " + p.getId() + " (" + p.getDisplayName()
                        + ") has no ready blob on disk — clients will not be able to download it.");
            }
        }

        Messenger messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, Reference.CHANNEL);
        messenger.registerIncomingPluginChannel(this, Reference.CHANNEL, new ChannelMessageListener(comms));

        getServer().getPluginManager().registerEvents(new ConnectionListener(comms), this);
        new SyncmaticaCommand(context).register(this);

        // Run the sweep on the same protocol thread that owns the exchanges it reaps, so it never races
        // packet handling. The body is guarded because a ScheduledExecutorService silently cancels a
        // periodic task that throws, which would stop sweeping for the rest of the session.
        staleSweepTask = protocolExecutor.scheduleWithFixedDelay(() -> {
            try {
                comms.sweepStaleExchanges(EXCHANGE_TIMEOUT_MILLIS);
            } catch (Throwable t) {
                getLogger().warning("Syncmatica stale sweep failed: " + t);
            }
        }, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);

        // On /reload, online players already fired PlayerRegisterChannelEvent before the listener
        // existed, so re-handshake anyone already listening on the channel.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getListeningPluginChannels().contains(Reference.CHANNEL)) {
                comms.execute(() -> comms.onChannelRegistered(p));
            }
        }

        getLogger().info("SyncmaticaPaper enabled (protocol " + config.getProtocolVersion()
                + ", features " + config.getFeatureSet() + ").");
    }

    @Override
    public void onDisable() {
        if (staleSweepTask != null) {
            staleSweepTask.cancel(false);
            staleSweepTask = null;
        }
        // Stop accepting new protocol work and let in-flight tasks drain, so the save() below reads a
        // quiescent placement set that no protocol task is still mutating.
        if (protocolExecutor != null) {
            protocolExecutor.shutdown();
            try {
                if (!protocolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Syncmatica protocol thread did not drain within 5s; forcing shutdown.");
                    protocolExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                protocolExecutor.shutdownNow();
            }
            protocolExecutor = null;
        }
        if (context != null) {
            context.syncManager.save();
        }
        getLogger().info("SyncmaticaPaper disabled.");
    }
}
