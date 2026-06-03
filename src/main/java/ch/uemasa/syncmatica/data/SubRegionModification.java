package ch.uemasa.syncmatica.data;

/**
 * A per-sub-region override of position/rotation/mirror. Rotation and mirror are raw wire ordinals.
 */
public record SubRegionModification(String name, int x, int y, int z, int rotation, int mirror) {
}
