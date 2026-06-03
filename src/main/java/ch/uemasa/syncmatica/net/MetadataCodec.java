package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.comm.Feature;
import ch.uemasa.syncmatica.comm.FeatureSet;
import ch.uemasa.syncmatica.data.PlayerIdentifier;
import ch.uemasa.syncmatica.data.PlayerIdentifierProvider;
import ch.uemasa.syncmatica.data.ServerPlacement;
import ch.uemasa.syncmatica.data.ServerPosition;
import ch.uemasa.syncmatica.data.SubRegionData;
import ch.uemasa.syncmatica.data.SubRegionModification;
import ch.uemasa.syncmatica.util.FileNameSanitizer;

import java.util.Map;
import java.util.UUID;

/**
 * Reads/writes placement metadata and position payloads. Optional fields are gated on the reader's
 * negotiated {@link FeatureSet}: a field is emitted only if the receiver advertised the feature.
 */
public final class MetadataCodec {

    /** Cap on the attacker-controlled sub-region count that drives the read allocation loop. */
    static final int MAX_SUB_REGIONS = 4096;

    private MetadataCodec() {
    }

    // ---- write ----

    public static void writeMetaData(SyncByteBuf buf, ServerPlacement p, FeatureSet reader) {
        buf.writeUUID(p.getId());
        buf.writeUtf(p.getFileName());
        buf.writeUUID(p.getHash());
        if (reader.hasFeature(Feature.DISPLAY_NAME)) {
            buf.writeUtf(p.getDisplayName());
        }
        if (reader.hasFeature(Feature.CORE_EX)) {
            PlayerIdentifier owner = p.getOwner();
            PlayerIdentifier last = p.getLastModifiedBy();
            buf.writeUUID(owner.uuid);
            buf.writeUtf(owner.getName());
            buf.writeUUID(last.uuid);
            buf.writeUtf(last.getName());
        }
        if (reader.hasFeature(Feature.VERSION)) {
            buf.writeVarInt(p.getLitematicVersion());
            buf.writeVarInt(p.getDataVersion());
        }
        writePositionData(buf, p, reader);
    }

    public static void writePositionData(SyncByteBuf buf, ServerPlacement p, FeatureSet reader) {
        ServerPosition pos = p.getOrigin();
        buf.writeBlockPos(pos.x(), pos.y(), pos.z());
        buf.writeUtf(pos.dimension());
        buf.writeInt(p.getRotation());
        buf.writeInt(p.getMirror());
        if (reader.hasFeature(Feature.CORE_EX)) {
            SubRegionData srd = p.getSubRegionData();
            if (!srd.isModified()) {
                buf.writeInt(0);
                return;
            }
            Map<String, SubRegionModification> mods = srd.getModifications();
            buf.writeInt(mods.size());
            for (SubRegionModification m : mods.values()) {
                buf.writeUtf(m.name());
                buf.writeBlockPos(m.x(), m.y(), m.z());
                buf.writeInt(m.rotation());
                buf.writeInt(m.mirror());
            }
        }
    }

    // ---- read ----

    public static ServerPlacement readMetaData(SyncByteBuf buf, FeatureSet reader, PlayerIdentifierProvider players) {
        UUID id = buf.readUUID();
        String fileName = FileNameSanitizer.normalize(buf.readUtf());
        UUID hash = buf.readUUID();
        String displayName = reader.hasFeature(Feature.DISPLAY_NAME) ? buf.readUtf() : fileName;

        PlayerIdentifier owner = PlayerIdentifierProvider.MISSING_PLAYER;
        PlayerIdentifier last = PlayerIdentifierProvider.MISSING_PLAYER;
        if (reader.hasFeature(Feature.CORE_EX)) {
            UUID ouid = buf.readUUID();
            String oname = buf.readUtf();
            owner = players.createOrGet(ouid, oname);
            UUID luid = buf.readUUID();
            String lname = buf.readUtf();
            last = players.createOrGet(luid, lname);
        }

        int litV = -1;
        int dataV = -1;
        if (reader.hasFeature(Feature.VERSION)) {
            litV = buf.readVarInt();
            dataV = buf.readVarInt();
        }

        ServerPlacement p = new ServerPlacement(id, hash, fileName, displayName, owner);
        p.setLastModifiedBy(last);
        p.setLitematicVersion(litV);
        p.setDataVersion(dataV);
        readPositionData(buf, p, reader);
        return p;
    }

    /** Reads position/sub-region data into an existing placement (used by metadata + modify_finish). */
    public static void readPositionData(SyncByteBuf buf, ServerPlacement p, FeatureSet reader) {
        int[] xyz = buf.readBlockPos();
        String dim = buf.readUtf();
        int rotation = buf.readInt();
        int mirror = buf.readInt();
        p.setOrigin(new ServerPosition(xyz[0], xyz[1], xyz[2], dim));
        p.setRotation(rotation);
        p.setMirror(mirror);

        SubRegionData srd = new SubRegionData();
        if (reader.hasFeature(Feature.CORE_EX)) {
            int count = buf.readInt();
            if (count < 0 || count > MAX_SUB_REGIONS) {
                throw new IllegalStateException("sub-region count out of range: " + count);
            }
            for (int i = 0; i < count; i++) {
                String name = buf.readUtf();
                int[] spos = buf.readBlockPos();
                int rot = buf.readInt();
                int mir = buf.readInt();
                srd.put(new SubRegionModification(name, spos[0], spos[1], spos[2], rot, mir));
            }
        }
        p.setSubRegionData(srd);
    }
}
