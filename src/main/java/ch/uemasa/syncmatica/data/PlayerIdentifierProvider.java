package ch.uemasa.syncmatica.data;

import ch.uemasa.syncmatica.Reference;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Interns {@link PlayerIdentifier}s so each UUID maps to a single shared instance. Identities are
 * persisted only indirectly, via the owner/lastModifiedBy embedded in each placement.
 */
public final class PlayerIdentifierProvider {

    public static final PlayerIdentifier MISSING_PLAYER =
            new PlayerIdentifier(Reference.MISSING_PLAYER_UUID, Reference.MISSING_PLAYER_NAME);

    private final Map<UUID, PlayerIdentifier> identifiers = new HashMap<>();

    public PlayerIdentifierProvider() {
        identifiers.put(Reference.MISSING_PLAYER_UUID, MISSING_PLAYER);
    }

    public PlayerIdentifier createOrGet(UUID uuid, String name) {
        // Server keeps the first-seen name; later (possibly remote-supplied) names never overwrite it.
        return identifiers.computeIfAbsent(uuid, u -> new PlayerIdentifier(u, name));
    }

    /** Authoritatively stamps a player's real name (use only for the connecting player, not remote data). */
    public PlayerIdentifier refresh(UUID uuid, String name) {
        PlayerIdentifier id = createOrGet(uuid, name);
        if (name != null && !uuid.equals(Reference.MISSING_PLAYER_UUID)) {
            id.updateName(name);
        }
        return id;
    }
}
