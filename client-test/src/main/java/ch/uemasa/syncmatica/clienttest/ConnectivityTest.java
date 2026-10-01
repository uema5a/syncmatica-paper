package ch.uemasa.syncmatica.clienttest;

import java.util.List;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Syncmatica;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joins a real Paper server running the plugin with the unmodified Syncmatica client and checks
 * that the session comes up and stays up.
 */
public class ConnectivityTest implements FabricClientGameTest {

    private static final int TICKS_PER_SECOND = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (PaperServer server = PaperServer.start()) {
            String address = "127.0.0.1:" + server.port();
            context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc,
                    ServerAddress.parseString(address), new ServerData("test", address, ServerData.Type.OTHER), false, null));

            context.waitFor(mc -> mc.player != null && mc.level != null, 60 * TICKS_PER_SECOND);

            // The client's Syncmatica context only starts once the server has completed the version
            // and feature handshake and sent the placement list.
            context.waitFor(mc -> clientSessionStarted(), 30 * TICKS_PER_SECOND);

            // A broken payload kills the connection on arrival, so give stray packets time to land.
            context.waitTicks(10 * TICKS_PER_SECOND);
            boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
            if (!connected) {
                throw new AssertionError("Client lost the connection after joining");
            }

            List<String> problems = server.lines(line -> line.contains("Exception") || line.contains("ERROR]"));
            if (!problems.isEmpty()) {
                throw new AssertionError("Server reported errors:\n" + String.join("\n", problems));
            }

            context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
            context.waitFor(mc -> mc.level == null, 10 * TICKS_PER_SECOND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean clientSessionStarted() {
        Context client = Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
        return client != null && client.isStarted();
    }
}
