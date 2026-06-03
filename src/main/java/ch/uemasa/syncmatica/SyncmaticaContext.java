package ch.uemasa.syncmatica;

import ch.uemasa.syncmatica.comm.FeatureSet;
import ch.uemasa.syncmatica.comm.ServerCommunicationManager;
import ch.uemasa.syncmatica.config.PluginConfig;
import ch.uemasa.syncmatica.config.QuotaService;
import ch.uemasa.syncmatica.data.FileStorage;
import ch.uemasa.syncmatica.data.PlayerIdentifierProvider;
import ch.uemasa.syncmatica.data.SyncmaticManager;

import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Holds the server-side services shared with the communication/exchange layer.
 */
public final class SyncmaticaContext {

    public final JavaPlugin plugin;
    public final Logger logger;
    public final PlayerIdentifierProvider players;
    public final SyncmaticManager syncManager;
    public final FileStorage fileStorage;

    // Mutable so /syncmatica reload can swap them.
    public volatile PluginConfig config;
    public volatile FeatureSet featureSet;
    public volatile QuotaService quota;

    private ServerCommunicationManager comms;

    public SyncmaticaContext(JavaPlugin plugin, PluginConfig config, PlayerIdentifierProvider players,
                             SyncmaticManager syncManager, FileStorage fileStorage, QuotaService quota) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.config = config;
        this.featureSet = config.getFeatureSet();
        this.players = players;
        this.syncManager = syncManager;
        this.fileStorage = fileStorage;
        this.quota = quota;
    }

    /** Applies a freshly-loaded config (used by {@code /syncmatica reload}). */
    public void applyConfig(PluginConfig newConfig) {
        this.config = newConfig;
        this.featureSet = newConfig.getFeatureSet();
        this.quota = new QuotaService(newConfig.isQuotaEnabled(), newConfig.getQuotaLimit());
    }

    public ServerCommunicationManager comms() {
        return comms;
    }

    public void setComms(ServerCommunicationManager comms) {
        this.comms = comms;
    }
}
