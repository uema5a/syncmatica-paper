package ch.uemasa.syncmatica.comm;

/** Severity tag for message packets, serialized by {@link #name()}. */
public enum MessageType {
    SUCCESS,
    INFO,
    WARNING,
    ERROR
}
