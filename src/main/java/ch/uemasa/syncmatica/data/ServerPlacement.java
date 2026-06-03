package ch.uemasa.syncmatica.data;

import java.util.UUID;

/**
 * One shared schematic placement (server-side state). Rotation and mirror are stored as raw wire
 * ordinals to avoid an NMS dependency.
 */
public final class ServerPlacement {

    private final UUID id;
    private final UUID hash;
    private String fileName;
    private String displayName;
    private PlayerIdentifier owner;
    private PlayerIdentifier lastModifiedBy;
    private ServerPosition origin = new ServerPosition(0, 0, 0, "minecraft:overworld");
    private int rotation;
    private int mirror;
    private SubRegionData subRegionData = new SubRegionData();
    private int litematicVersion = -1;
    private int dataVersion = -1;

    public ServerPlacement(UUID id, UUID hash, String fileName, String displayName,
                           PlayerIdentifier owner) {
        this.id = id;
        this.hash = hash;
        this.fileName = fileName;
        this.displayName = displayName;
        this.owner = owner;
        this.lastModifiedBy = owner;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHash() {
        return hash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public PlayerIdentifier getOwner() {
        return owner;
    }

    public void setOwner(PlayerIdentifier owner) {
        this.owner = owner;
    }

    public PlayerIdentifier getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(PlayerIdentifier lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public ServerPosition getOrigin() {
        return origin;
    }

    public void setOrigin(ServerPosition origin) {
        this.origin = origin;
    }

    public int getRotation() {
        return rotation;
    }

    public void setRotation(int rotation) {
        this.rotation = rotation;
    }

    public int getMirror() {
        return mirror;
    }

    public void setMirror(int mirror) {
        this.mirror = mirror;
    }

    public SubRegionData getSubRegionData() {
        return subRegionData;
    }

    public void setSubRegionData(SubRegionData subRegionData) {
        this.subRegionData = subRegionData;
    }

    public int getLitematicVersion() {
        return litematicVersion;
    }

    public void setLitematicVersion(int litematicVersion) {
        this.litematicVersion = litematicVersion;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(int dataVersion) {
        this.dataVersion = dataVersion;
    }
}
