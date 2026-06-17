package ch.uemasa.syncmatica;

import ch.uemasa.syncmatica.comm.ServerCommunicationManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

/**
 * Starts the handshake on join and tears down connection state on quit. Upstream initiates on player
 * join (the client registers the payload codec but does not announce the channel via minecraft:register,
 * so PlayerRegisterChannelEvent may never fire); the register-channel handler is kept only as a fallback
 * for clients that do announce. {@code onChannelRegistered} is idempotent, so a double trigger is safe.
 */
public final class ConnectionListener implements Listener {

    private final ServerCommunicationManager comms;

    public ConnectionListener(ServerCommunicationManager comms) {
        this.comms = comms;
    }

    // Each handler hands off to the single protocol thread: on Folia these events fire on the player's
    // region thread, so onChannelRegistered/onPlayerQuit must not touch the shared state directly.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        comms.execute(() -> comms.onChannelRegistered(player));
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (Reference.CHANNEL.equals(event.getChannel())) {
            Player player = event.getPlayer();
            comms.execute(() -> comms.onChannelRegistered(player));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        comms.execute(() -> comms.onPlayerQuit(player));
    }
}
