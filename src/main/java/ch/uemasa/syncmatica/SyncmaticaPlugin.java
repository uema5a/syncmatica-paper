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
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;

/**
 * Plugin entry point. Wires the server-side Syncmatica services and registers the channel,
 * connection listener, and {@code /syncmatica} command.
 */
public final class SyncmaticaPlugin extends JavaPlugin {

    private static final long EXCHANGE_TIMEOUT_MILLIS = 60_000L;
    private static final long SWEEP_PERIOD_TICKS = 20L * 20L;

    private SyncmaticaContext context;
    private BukkitTask staleSweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig config = PluginConfig.load(getConfig());

        Path dataFolder = getDataFolder().toPath();
        Path blobFolder = dataFolder.resolve("syncmatics");
        Path placementsFile = dataFolder.resolve("placements.json");

        PlayerIdentifierProvider players = new PlayerIdentifierProvider();
        SyncmaticManager syncManager = new SyncmaticManager(placementsFile, players, getLogger());
        FileStorage fileStorage = new FileStorage(blobFolder);
        QuotaService quota = new QuotaService(config.isQuotaEnabled(), config.getQuotaLimit());

        context = new SyncmaticaContext(this, config, players, syncManager, fileStorage, quota);
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

        staleSweepTask = getServer().getScheduler().runTaskTimer(
                this, () -> comms.sweepStaleExchanges(EXCHANGE_TIMEOUT_MILLIS),
                SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);

        // On /reload, online players already fired PlayerRegisterChannelEvent before the listener
        // existed, so re-handshake anyone already listening on the channel.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getListeningPluginChannels().contains(Reference.CHANNEL)) {
                comms.onChannelRegistered(p);
            }
        }

        getLogger().info("SyncmaticaPaper enabled (protocol " + config.getProtocolVersion()
                + ", features " + config.getFeatureSet() + ").");
    }

    @Override
    public void onDisable() {
        if (staleSweepTask != null) {
            staleSweepTask.cancel();
            staleSweepTask = null;
        }
        if (context != null) {
            context.syncManager.save();
        }
        getLogger().info("SyncmaticaPaper disabled.");
    }
}
