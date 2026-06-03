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
        comms.onPacket(player, message);
    }
}
