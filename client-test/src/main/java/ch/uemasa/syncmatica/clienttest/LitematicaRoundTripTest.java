package ch.uemasa.syncmatica.clienttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.communication.exchange.ModifyExchangeClient;
import ch.endte.syncmatica.communication.exchange.ShareLitematicExchange;
import ch.endte.syncmatica.data.LocalLitematicState;
import ch.endte.syncmatica.data.ServerPlacement;
import ch.endte.syncmatica.extended_core.SubRegionPlacementModification;
import ch.endte.syncmatica.litematica.LitematicManager;
import ch.endte.syncmatica.network.PacketType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives the same Syncmatica/Litematica code paths the in-game buttons use and checks every step
 * against what the server actually stored: share a placement, download it back, rotate/mirror/move
 * it and one of its subregions, restart the server and check everything was loaded back from disk,
 * then remove it.
 */
public class LitematicaRoundTripTest implements FabricClientGameTest {

    private static final String NAME = "syncmatica-roundtrip";
    private static final String SECOND_REGION = "second";
    private static final int TIMEOUT = 30 * Session.TICKS_PER_SECOND;
    // Syncmatica sends files in chunks of this size; the test schematic has to be bigger.
    private static final int TRANSFER_CHUNK = 16384;

    @Override
    public void runTest(ClientGameTestContext context) {
        PaperServer server = null;
        try {
            server = PaperServer.start();
            PaperServer srv = server;
            Session.join(context, server);

            // Share.
            SchematicPlacement local = context.computeOnClient(mc -> createLocalPlacement(mc.player.blockPosition()));
            context.runOnClient(mc -> {
                Context con = Session.client();
                con.getCommunicationManager().startExchange(new ShareLitematicExchange(local, Session.server(), con));
            });
            context.waitFor(mc -> placements().size() == 1, TIMEOUT);
            ServerPlacement shared = context.computeOnClient(mc -> placements().iterator().next());
            UUID id = context.computeOnClient(mc -> shared.getId());
            UUID hash = context.computeOnClient(mc -> shared.getHash());
            Path blob = server.pluginData().resolve("syncmatics/" + hash + ".litematic");
            check(Files.isRegularFile(blob), "server did not store the schematic");
            check(Files.size(blob) > TRANSFER_CHUNK, "test schematic fits in one chunk (" + Files.size(blob) + " bytes)");
            check(storedPlacement(server, id) != null, "server did not persist the placement");

            // Download: drop the local copy and fetch it again. The client only reports the file as
            // present once its hash matches the server's.
            context.runOnClient(mc -> {
                LitematicManager.getInstance().unrenderSyncmatic(shared);
                Path file = Session.client().getFileStorage().getLocalLitematic(shared);
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

            // Modify: unlock through the server, then rotate, mirror and move the placement and one
            // subregion, and commit, like the placement screen does.
            context.runOnClient(mc -> {
                Context con = Session.client();
                con.getCommunicationManager().startExchange(new ModifyExchangeClient(shared, Session.server(), con));
            });
            context.waitFor(mc -> !rendered.isLocked(), TIMEOUT);
            BlockPos moved = context.computeOnClient(mc -> rendered.getOrigin().offset(5, 0, 3));
            context.runOnClient(mc -> {
                rendered.setOrigin(moved, null);
                rendered.setRotation(Rotation.CLOCKWISE_90, null);
                rendered.setMirror(Mirror.LEFT_RIGHT, null);
                rendered.moveSubRegionTo(SECOND_REGION, moved.offset(0, 20, 0), null);
                ((ModifyExchangeClient) Session.client().getCommunicationManager().getModifier(shared)).conclude();
            });
            SubRegionPlacement expectedRegion = context.computeOnClient(mc -> rendered.getRelativeSubRegionPlacement(SECOND_REGION));
            BlockPos expectedRegionPos = context.computeOnClient(mc -> expectedRegion.getPos());
            Rotation expectedRegionRotation = context.computeOnClient(mc -> expectedRegion.getRotation());
            Mirror expectedRegionMirror = context.computeOnClient(mc -> expectedRegion.getMirror());
            // The client re-locks right after sending its commit, so wait for the server's copy.
            context.waitFor(mc -> {
                JsonObject stored = storedPlacement(srv, id);
                return stored != null
                        && position(stored.getAsJsonObject("origin")).equals(moved)
                        && "CLOCKWISE_90".equals(stored.get("rotation").getAsString())
                        && "LEFT_RIGHT".equals(stored.get("mirror").getAsString())
                        && stored.has("subregionData");
            }, TIMEOUT);
            // The server broadcasts the change back to everyone, including us.
            context.waitTicks(2 * Session.TICKS_PER_SECOND);
            Session.assertConnected(context);

            // Restart: everything below has to come back from placements.json on disk.
            Session.leave(context, server);
            server = server.restart();
            Session.join(context, server);
            context.waitFor(mc -> placement(id) != null, TIMEOUT);
            context.runOnClient(mc -> {
                ServerPlacement p = placement(id);
                check(p.getPosition().equals(moved), "position " + p.getPosition() + ", expected " + moved);
                check(p.getRotation() == Rotation.CLOCKWISE_90, "rotation " + p.getRotation());
                check(p.getMirror() == Mirror.LEFT_RIGHT, "mirror " + p.getMirror());
                check(p.getHash().equals(hash), "hash changed");
                // The sharing client sends its local path; the server keeps only the file name.
                String fileName = NAME + LitematicaSchematic.FILE_EXTENSION;
                check(p.getFileName().equals(fileName), "file name " + p.getFileName() + ", expected " + fileName);
                check(p.getOwner().getName().equals(mc.player.getGameProfile().name()), "owner " + p.getOwner().getName());
                SubRegionPlacementModification region = p.getSubRegionData().getModificationData().get(SECOND_REGION);
                check(region != null, "subregion modification lost");
                check(region.position.equals(expectedRegionPos), "subregion at " + region.position + ", expected " + expectedRegionPos);
                check(region.rotation == expectedRegionRotation, "subregion rotation " + region.rotation);
                check(region.mirror == expectedRegionMirror, "subregion mirror " + region.mirror);
            });

            // Remove: gone from the client, from placements.json and from disk, and stays gone.
            context.runOnClient(mc -> {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeUUID(id);
                Session.server().sendPacket(PacketType.REMOVE_SYNCMATIC, buf, Session.client());
            });
            context.waitFor(mc -> placement(id) == null, TIMEOUT);
            context.waitFor(mc -> storedPlacement(srv, id) == null && !Files.exists(blob), TIMEOUT);
            Session.leave(context, server);
            Session.join(context, server);
            context.waitTicks(Session.TICKS_PER_SECOND);
            check(context.computeOnClient(mc -> placements().isEmpty()), "placement came back after removal");

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

    /**
     * Builds a two-region schematic filled with random blocks, so it neither compresses below one
     * transfer chunk nor has a trivial layout, saves it and places it in Litematica.
     */
    private static SchematicPlacement createLocalPlacement(BlockPos at) {
        AreaSelection area = new AreaSelection();
        area.setName(NAME);
        area.addSubRegionBox(new Box(at, at.offset(39, 15, 39), "main"), false);
        area.addSubRegionBox(new Box(at.offset(45, 0, 0), at.offset(52, 7, 7), SECOND_REGION), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "test");

        List<BlockState> palette = new ArrayList<>();
        for (DyeColor color : DyeColor.values()) {
            for (String kind : List.of("_wool", "_concrete", "_terracotta")) {
                palette.add(BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(color.getName() + kind)).defaultBlockState());
            }
        }
        Random random = new Random(42);
        for (String region : List.of("main", SECOND_REGION)) {
            BlockPos size = schematic.getAreaSize(region);
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            for (int x = 0; x < Math.abs(size.getX()); x++) {
                for (int y = 0; y < Math.abs(size.getY()); y++) {
                    for (int z = 0; z < Math.abs(size.getZ()); z++) {
                        container.set(x, y, z, palette.get(random.nextInt(palette.size())));
                    }
                }
            }
        }

        Path dir = DataManager.getSchematicsBaseDirectory();
        if (!schematic.writeToFile(dir, NAME, true)) {
            throw new AssertionError("could not save the test schematic");
        }
        LitematicaSchematic saved = LitematicaSchematic.createFromFile(dir, NAME + LitematicaSchematic.FILE_EXTENSION);
        SchematicPlacement placement = SchematicPlacement.createFor(saved, at.offset(3, 0, 3), NAME, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    /** The placement as the server last saved it to placements.json, or null if it isn't there. */
    private static JsonObject storedPlacement(PaperServer server, UUID id) {
        Path file = server.pluginData().resolve("placements.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("placements")) {
                JsonObject o = e.getAsJsonObject();
                if (o.get("id").getAsString().equals(id.toString())) {
                    return o;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            // Caught mid-write; the next poll will see the finished file.
            return null;
        }
    }

    private static BlockPos position(JsonObject origin) {
        JsonArray xyz = origin.getAsJsonArray("position");
        return new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
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
