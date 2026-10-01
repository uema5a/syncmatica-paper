package ch.uemasa.syncmatica.clienttest;

import java.util.List;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Syncmatica;
import ch.endte.syncmatica.communication.ClientCommunicationManager;
import ch.endte.syncmatica.communication.ExchangeTarget;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Joining and leaving the test server, plus access to the client's Syncmatica session. */
final class Session {

    static final int TICKS_PER_SECOND = 20;

    private Session() {
    }

    /** Joins the server and waits until the Syncmatica handshake has completed. */
    static void join(ClientGameTestContext context, PaperServer server) {
        String address = "127.0.0.1:" + server.port();
        context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc,
                ServerAddress.parseString(address), new ServerData("test", address, ServerData.Type.OTHER), false, null));
        context.waitFor(mc -> mc.player != null && mc.level != null, 60 * TICKS_PER_SECOND);
        // The client's context only starts once the server has finished the version and feature
        // handshake and sent the placement list.
        context.waitFor(mc -> {
            Context client = client();
            return client != null && client.isStarted();
        }, 30 * TICKS_PER_SECOND);
    }

    static void leave(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.disconnect(new TitleScreen(), false));
        context.waitFor(mc -> mc.level == null, 10 * TICKS_PER_SECOND);
    }

    static void assertConnected(ClientGameTestContext context) {
        boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
        if (!connected) {
            throw new AssertionError("Client lost the connection");
        }
    }

    static void assertServerClean(PaperServer server) {
        List<String> problems = server.lines(line -> line.contains("Exception") || line.contains("ERROR]"));
        if (!problems.isEmpty()) {
            throw new AssertionError("Server reported errors:\n" + String.join("\n", problems));
        }
    }

    static Context client() {
        return Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
    }

    static ExchangeTarget server() {
        return ((ClientCommunicationManager) client().getCommunicationManager()).getServer();
    }
}
