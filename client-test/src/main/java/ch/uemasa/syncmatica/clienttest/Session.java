package ch.uemasa.syncmatica.clienttest;

import java.util.List;
import java.util.regex.Pattern;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Syncmatica;
import ch.endte.syncmatica.communication.ClientCommunicationManager;
import ch.endte.syncmatica.communication.ExchangeTarget;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Joining and leaving the test server, plus access to the client's Syncmatica session. */
final class Session {

    static final int TICKS_PER_SECOND = 20;

    // Anything the plugin logs above INFO is a failure: it reports most problems (hash mismatch,
    // failed saves, refused exchanges) as warnings rather than exceptions.
    private static final Pattern PLUGIN_WARNING = Pattern.compile("(WARN|ERROR)]: \\[SyncmaticaPaper]");
    private static final Pattern THROWABLE = Pattern.compile("\\b\\w+(Exception|Error)\\b|ERROR]");

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
        bakeMessageGlyphs(context);
    }

    /**
     * The Syncmatica client shows some messages straight from the network thread (e.g. when a
     * modification is accepted), and MaLiLib measures the text there. Measuring a glyph that was
     * never drawn before bakes it into the font texture, which throws off the render thread and
     * kills the connection. A real client has usually drawn these characters already; the test
     * client hasn't, so draw them once on the render thread first.
     */
    private static void bakeMessageGlyphs(ClientGameTestContext context) {
        StringBuilder ascii = new StringBuilder();
        for (char c = 0x20; c < 0x7f; c++) {
            ascii.append(c);
        }
        context.runOnClient(mc -> {
            mc.font.width(ascii.toString());
            for (String key : List.of("syncmatica.success.modification_accepted", "syncmatica.error.modification_deny")) {
                mc.font.width(I18n.get(key));
            }
        });
    }

    /** Disconnects and waits until the server has let go of the player too. */
    static void leave(ClientGameTestContext context, PaperServer server) {
        int before = server.lines(line -> line.contains("lost connection")).size();
        // Same call as the pause menu's Disconnect button; it actually closes the connection.
        context.runOnClient(mc -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE));
        context.waitFor(mc -> mc.level == null, 10 * TICKS_PER_SECOND);
        context.waitFor(mc -> server.lines(line -> line.contains("lost connection")).size() > before, 10 * TICKS_PER_SECOND);
        // The pause menu drops back to the server list; gametests have to end on the title screen.
        context.setScreen(TitleScreen::new);
    }

    static void assertConnected(ClientGameTestContext context) {
        boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
        if (!connected) {
            throw new AssertionError("Client lost the connection");
        }
    }

    static void assertServerClean(PaperServer server) {
        List<String> problems = server.lines(line -> PLUGIN_WARNING.matcher(line).find() || THROWABLE.matcher(line).find());
        if (!problems.isEmpty()) {
            throw new AssertionError("Server reported problems:\n" + String.join("\n", problems));
        }
    }

    /** The client's Syncmatica context. Only touch it on the client thread. */
    static Context client() {
        return Syncmatica.getContext(Syncmatica.CLIENT_CONTEXT);
    }

    static ExchangeTarget server() {
        return ((ClientCommunicationManager) client().getCommunicationManager()).getServer();
    }
}
