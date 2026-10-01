package ch.uemasa.syncmatica.clienttest.helper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Server-side test hooks, driven from the server console by the client tests. Not part of the
 * plugin; it only exists on the throwaway test server.
 */
public final class SyncTestHelper extends JavaPlugin implements Listener {

    // How long a reconfigured player is held in the configuration phase, so anything sent to them
    // in the meantime has time to arrive there.
    private static final long RECONFIGURATION_TICKS = 40;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equals("reconfigure-and-send")) {
            Player player = getServer().getPlayerExact(args[1]);
            if (player == null) {
                getLogger().warning("No such player: " + args[1]);
                return true;
            }
            // Same tick: the player is switched to the configuration phase, then the plugin under
            // test is asked to send them something.
            player.getConnection().reenterConfiguration();
            sendThroughPlugin(player);
            getLogger().info("Sent a Syncmatica packet right after reconfiguring " + player.getName());
            return true;
        }
        return false;
    }

    @EventHandler
    public void onReconfigure(PlayerConnectionReconfigureEvent event) {
        getServer().getScheduler().runTaskLater(this, () -> event.getConnection().completeReconfiguration(), RECONFIGURATION_TICKS);
    }

    /** Sends a harmless feature request through the plugin's own transport. */
    private void sendThroughPlugin(Player player) {
        try {
            Plugin syncmatica = getServer().getPluginManager().getPlugin("SyncmaticaPaper");
            Class<?> rawChannel = Class.forName("ch.uemasa.syncmatica.net.RawChannel", true, syncmatica.getClass().getClassLoader());
            rawChannel.getMethod("send", Player.class, byte[].class).invoke(null, player, identifier("syncmatica:feature_request"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Syncmatica packet with no body: just the length-prefixed packet type. */
    private static byte[] identifier(String id) {
        byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = bytes.length;
        while ((length & ~0x7f) != 0) {
            out.write((length & 0x7f) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }
}
