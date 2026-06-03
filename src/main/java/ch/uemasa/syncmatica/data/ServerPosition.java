package ch.uemasa.syncmatica.data;

/**
 * A placement origin: integer block coordinates plus a dimension id string (e.g. {@code minecraft:overworld}).
 */
public record ServerPosition(int x, int y, int z, String dimension) {
}
