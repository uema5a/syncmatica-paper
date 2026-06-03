package ch.uemasa.syncmatica.net;

import ch.uemasa.syncmatica.Reference;

import java.util.HashMap;
import java.util.Map;

/**
 * Application-level message types carried in the {@code syncmatica:main} channel; each value's
 * {@link #id()} is the Identifier written as the first field of every payload.
 */
public enum PacketType {
    REGISTER_METADATA("register_metadata"),
    CANCEL_SHARE("cancel_share"),
    REQUEST_LITEMATIC("request_download"), // intentional wire string (not "request_litematic")
    SEND_LITEMATIC("send_litematic"),
    RECEIVED_LITEMATIC("received_litematic"),
    FINISHED_LITEMATIC("finished_litematic"),
    CANCEL_LITEMATIC("cancel_litematic"),
    REMOVE_SYNCMATIC("remove_syncmatic"),
    REGISTER_VERSION("register_version"),
    CONFIRM_USER("confirm_user"),
    FEATURE_REQUEST("feature_request"),
    FEATURE("feature"),
    MODIFY("modify"),
    MODIFY_REQUEST("modify_request"),
    MODIFY_REQUEST_DENY("modify_request_deny"),
    MODIFY_REQUEST_ACCEPT("modify_request_accept"),
    MODIFY_FINISH("modify_finish"),
    MESSAGE("mesage"); // intentional upstream typo (one 's'), must match

    private final String path;
    private final String id;

    PacketType(String path) {
        this.path = path;
        this.id = Reference.MOD_ID + ":" + path;
    }

    public String path() {
        return path;
    }

    /** The full {@code "syncmatica:<path>"} Identifier string written on the wire. */
    public String id() {
        return id;
    }

    private static final Map<String, PacketType> BY_ID = new HashMap<>();

    static {
        for (PacketType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    /** @return the matching type, or {@code null} if the id is unknown. */
    public static PacketType fromId(String id) {
        return BY_ID.get(id);
    }
}
