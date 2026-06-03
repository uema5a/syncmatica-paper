package ch.uemasa.syncmatica.data;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's persistent identity (UUID + last-seen name) used for placement attribution.
 * Equality is by UUID only.
 */
public final class PlayerIdentifier {

    public final UUID uuid;
    private String name;

    PlayerIdentifier(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    void updateName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerIdentifier other && other.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uuid);
    }
}
