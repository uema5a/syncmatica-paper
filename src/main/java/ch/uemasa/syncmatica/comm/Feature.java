package ch.uemasa.syncmatica.comm;

/**
 * Negotiable protocol capabilities, serialized on the wire by {@link #name()} (e.g. {@code "CORE_EX"}).
 */
public enum Feature {
    CORE,
    FEATURE,
    MODIFY,
    MESSAGE,
    QUOTA,
    DEBUG,
    CORE_EX,
    VERSION,
    DISPLAY_NAME;

    public static Feature fromString(String s) {
        for (Feature f : values()) {
            if (f.name().equals(s)) {
                return f;
            }
        }
        return null;
    }
}
