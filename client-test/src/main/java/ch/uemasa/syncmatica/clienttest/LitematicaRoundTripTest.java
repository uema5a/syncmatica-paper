package ch.uemasa.syncmatica.clienttest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.communication.exchange.ModifyExchangeClient;
import ch.endte.syncmatica.communication.exchange.ShareLitematicExchange;
import ch.endte.syncmatica.data.LocalLitematicState;
import ch.endte.syncmatica.data.ServerPlacement;
import ch.endte.syncmatica.litematica.LitematicManager;
import ch.endte.syncmatica.network.PacketType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Drives the same Syncmatica/Litematica code paths the in-game buttons use: share a Litematica
 * placement, download it back from the server, move it, see the move survive a reconnect, then
 * remove it.
 */
public class LitematicaRoundTripTest implements FabricClientGameTest {

    private static final String NAME = "syncmatica-roundtrip";
    private static final int TIMEOUT = 30 * Session.TICKS_PER_SECOND;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (PaperServer server = PaperServer.start()) {
            Session.join(context, server);
            // Let the chunks around the player arrive so there is something to save.
            context.waitTicks(3 * Session.TICKS_PER_SECOND);

            // Share.
            SchematicPlacement local = context.computeOnClient(mc -> createLocalPlacement(mc.player.blockPosition()));
            context.runOnClient(mc -> {
                Context con = Session.client();
                con.getCommunicationManager().startExchange(new ShareLitematicExchange(local, Session.server(), con));
            });
            context.waitFor(mc -> placements().size() == 1, TIMEOUT);
            ServerPlacement shared = placements().iterator().next();
            UUID id = shared.getId();
            BlockPos origin = shared.getPosition();
            check(origin.equals(local.getOrigin()), "shared origin " + origin + " != local " + local.getOrigin());
            check(Files.isRegularFile(server.pluginData().resolve("syncmatics/" + shared.getHash() + ".litematic")),
                    "server did not store the schematic");
            check(Files.readString(server.pluginData().resolve("placements.json")).contains(id.toString()),
                    "server did not persist the placement");

            // Download: drop the local copy and fetch it again. The client only reports the file as
            // present once its hash matches the server's.
            context.runOnClient(mc -> {
                Context con = Session.client();
                LitematicManager.getInstance().unrenderSyncmatic(shared);
                Path file = con.getFileStorage().getLocalLitematic(shared);
                if (file != null) {
                    Files.delete(file);
                }
            });
            context.waitFor(mc -> localState(shared) == LocalLitematicState.NO_LOCAL_LITEMATIC, TIMEOUT);
            context.runOnClient(mc -> Session.client().getCommunicationManager().download(shared, Session.server()));
            context.waitFor(mc -> localState(shared) == LocalLitematicState.LOCAL_LITEMATIC_PRESENT, TIMEOUT);
            context.runOnClient(mc -> LitematicManager.getInstance().renderSyncmatic(shared));
            context.waitFor(mc -> LitematicManager.getInstance().isRendered(shared), TIMEOUT);
            SchematicPlacement rendered = context.computeOnClient(mc -> LitematicManager.getInstance().schematicFromSyncmatic(shared));
            check(rendered.getOrigin().equals(origin), "downloaded placement at " + rendered.getOrigin() + ", expected " + origin);

            // Modify: unlock through the server, move, and commit, like the placement screen does.
            BlockPos moved = origin.offset(5, 0, 3);
            context.runOnClient(mc -> {
                Context con = Session.client();
                con.getCommunicationManager().startExchange(new ModifyExchangeClient(shared, Session.server(), con));
            });
            context.waitFor(mc -> !rendered.isLocked(), TIMEOUT);
            context.runOnClient(mc -> {
                rendered.setOrigin(moved, null);
                ((ModifyExchangeClient) Session.client().getCommunicationManager().getModifier(shared)).conclude();
            });
            context.waitFor(mc -> rendered.isLocked(), TIMEOUT);

            // Reconnect: the move has to come back from the server, not from client memory.
            Session.leave(context);
            Session.join(context, server);
            context.waitFor(mc -> placement(id) != null, TIMEOUT);
            BlockPos afterRejoin = placement(id).getPosition();
            check(afterRejoin.equals(moved), "after reconnect placement is at " + afterRejoin + ", expected " + moved);

            // Remove, then make sure it stays gone across a reconnect too.
            context.runOnClient(mc -> {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeUUID(id);
                Session.server().sendPacket(PacketType.REMOVE_SYNCMATIC, buf, Session.client());
            });
            context.waitFor(mc -> placement(id) == null, TIMEOUT);
            Session.leave(context);
            Session.join(context, server);
            context.waitTicks(Session.TICKS_PER_SECOND);
            check(placements().isEmpty(), "placement came back after removal");

            Session.assertConnected(context);
            Session.assertServerClean(server);
            Session.leave(context);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Saves a small schematic of the ground under the player and places it in Litematica. */
    private static SchematicPlacement createLocalPlacement(BlockPos at) {
        AreaSelection area = new AreaSelection();
        area.setName(NAME);
        area.addSubRegionBox(new Box(at.offset(-1, -2, -1), at.offset(1, 0, 1), "main"), false);
        LitematicaSchematic captured = LitematicaSchematic.createFromWorld(
                net.minecraft.client.Minecraft.getInstance().level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false), "test", msg -> { });
        Path dir = DataManager.getSchematicsBaseDirectory();
        if (captured == null || !captured.writeToFile(dir, NAME, true)) {
            throw new AssertionError("could not save the test schematic");
        }
        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, NAME + LitematicaSchematic.FILE_EXTENSION);
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, at.offset(3, 0, 3), NAME, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    private static Collection<ServerPlacement> placements() {
        return Session.client().getSyncmaticManager().getAll();
    }

    private static ServerPlacement placement(UUID id) {
        return Session.client().getSyncmaticManager().getPlacement(id);
    }

    private static LocalLitematicState localState(ServerPlacement placement) {
        return Session.client().getFileStorage().getLocalState(placement);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
