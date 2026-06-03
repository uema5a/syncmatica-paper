package ch.uemasa.syncmatica;

import ch.uemasa.syncmatica.comm.ServerCommunicationManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

/**
 * Starts the handshake when a client registers the channel and tears down connection state on quit.
 * {@link PlayerRegisterChannelEvent} is a more reliable trigger than join: it fires only once the
 * client has actually registered the channel.
 */
public final class ConnectionListener implements Listener {

    private final ServerCommunicationManager comms;

    public ConnectionListener(ServerCommunicationManager comms) {
        this.comms = comms;
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (Reference.CHANNEL.equals(event.getChannel())) {
            comms.onChannelRegistered(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        comms.onPlayerQuit(event.getPlayer());
    }
}
