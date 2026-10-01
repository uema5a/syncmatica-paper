package ch.uemasa.syncmatica.clienttest;

import java.util.List;

import ch.endte.syncmatica.Feature;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Joins a real Paper server running the plugin with the unmodified Syncmatica client and checks
 * that the session comes up with the full feature set and stays up.
 */
public class ConnectivityTest implements FabricClientGameTest {

    // The client silently falls back to older flows when a feature is missing, so check for the
    // ones the rest of the tests rely on rather than finding out from a confusing failure later.
    private static final List<Feature> EXPECTED = List.of(
            Feature.CORE, Feature.FEATURE, Feature.MODIFY, Feature.MESSAGE, Feature.CORE_EX, Feature.DISPLAY_NAME);

    @Override
    public void runTest(ClientGameTestContext context) {
        PaperServer server = null;
        try {
            server = PaperServer.start();
            Session.join(context, server);

            List<Feature> missing = context.computeOnClient(mc -> EXPECTED.stream()
                    .filter(f -> !Session.server().getFeatureSet().hasFeature(f))
                    .toList());
            if (!missing.isEmpty()) {
                throw new AssertionError("Server did not negotiate " + missing);
            }

            // A broken payload kills the connection on arrival, so give stray packets time to land.
            context.waitTicks(10 * Session.TICKS_PER_SECOND);
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
