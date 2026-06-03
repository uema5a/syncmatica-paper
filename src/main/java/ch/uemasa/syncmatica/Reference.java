package ch.uemasa.syncmatica;

import java.util.UUID;

/**
 * Protocol-level constants; wire values must match the unmodified Syncmatica client.
 */
public final class Reference {

    private Reference() {
    }

    public static final String MOD_ID = "syncmatica";

    public static final String CHANNEL = MOD_ID + ":main";

    // The Syncmatica release whose protocol this is compatible with. Must not reduce to "0.1"
    // (the client hard-codes that to CORE-only, skipping feature negotiation) nor equal the
    // "0.0.1" too-old sentinel.
    public static final String DEFAULT_MOD_VERSION = "0.3.18";

    /** The client's "incompatible / too old" sentinel; the only version rejected on join. */
    public static final String INCOMPATIBLE_VERSION = "0.0.1";

    public static final int PACKET_MAX_STRING_SIZE = 32767;

    public static final int BUFFER_SIZE = 16384;

    public static final UUID MISSING_PLAYER_UUID = UUID.fromString("4c1b738f-56fa-4011-8273-498c972424ea");
    public static final String MISSING_PLAYER_NAME = "No Player";
}
