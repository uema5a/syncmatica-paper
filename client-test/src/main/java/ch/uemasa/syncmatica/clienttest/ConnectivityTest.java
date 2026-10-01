package ch.uemasa.syncmatica.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Joins a real Paper server running the plugin with the unmodified Syncmatica client and checks
 * that the session comes up and stays up.
 */
public class ConnectivityTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (PaperServer server = PaperServer.start()) {
            Session.join(context, server);

            // A broken payload kills the connection on arrival, so give stray packets time to land.
            context.waitTicks(10 * Session.TICKS_PER_SECOND);
            Session.assertConnected(context);
            Session.assertServerClean(server);

            Session.leave(context);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
