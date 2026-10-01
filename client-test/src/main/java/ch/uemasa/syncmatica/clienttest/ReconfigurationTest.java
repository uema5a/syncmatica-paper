package ch.uemasa.syncmatica.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A player can be sent back to the configuration phase mid-game (proxies, resource pack and
 * login plugins do this). The Syncmatica client only understands its packets during play and
 * crashes the connection if one arrives during configuration (#10), so the plugin must not send
 * any while the player is there, and has to pick the session back up once they return.
 */
public class ReconfigurationTest implements FabricClientGameTest {

    private static final int TIMEOUT = 30 * Session.TICKS_PER_SECOND;

    @Override
    public void runTest(ClientGameTestContext context) {
        PaperServer server = null;
        try {
            server = PaperServer.start();
            PaperServer srv = server;
            Session.join(context, server);
            int disconnects = server.lines(line -> line.contains("lost connection")).size();

            server.command("synctest reconfigure-and-send " + context.computeOnClient(mc -> mc.player.getGameProfile().name()));
            context.waitFor(mc -> !srv.lines(line -> line.contains("right after reconfiguring")).isEmpty(), TIMEOUT);

            // Back in play with a fresh Syncmatica session, and never dropped on the way.
            context.waitFor(mc -> mc.player != null && mc.getConnection() != null
                    && Session.client() != null && Session.client().isStarted()
                    || srv.lines(line -> line.contains("lost connection")).size() > disconnects, TIMEOUT);
            if (server.lines(line -> line.contains("lost connection")).size() > disconnects) {
                throw new AssertionError("Client was disconnected while reconfiguring");
            }
            context.waitTicks(2 * Session.TICKS_PER_SECOND);
            Session.assertConnected(context);

            Session.leave(context, server);
            server.close();
            Session.assertServerClean(server);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }
}
