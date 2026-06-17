package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.Reference;
import ch.uemasa.syncmatica.comm.ServerCommunicationManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Receives raw {@code syncmatica:main} plugin messages and hands them to the communication manager.
 */
public final class ChannelMessageListener implements PluginMessageListener {

    private final ServerCommunicationManager comms;

    public ChannelMessageListener(ServerCommunicationManager comms) {
        this.comms = comms;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Reference.CHANNEL.equals(channel)) {
            return;
        }
        // On Folia this fires on the player's region thread (several players in parallel); hand off to the
        // single protocol thread so all connection/exchange state stays single-threaded. message is already
        // a private copy from Bukkit and the player reference is stable, so both are safe to capture.
        comms.execute(() -> comms.onPacket(player, message));
    }
}
