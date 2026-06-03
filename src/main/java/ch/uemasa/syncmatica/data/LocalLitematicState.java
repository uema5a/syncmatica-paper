package ch.uemasa.syncmatica.data;

/**
 * State of a placement's backing {@code .litematic} file in server storage.
 */
public enum LocalLitematicState {
    NO_LOCAL_LITEMATIC(true, false),
    LOCAL_LITEMATIC_DESYNC(true, false),
    DOWNLOADING_LITEMATIC(false, false),
    LOCAL_LITEMATIC_PRESENT(false, true);

    private final boolean downloadReady;
    private final boolean fileReady;

    LocalLitematicState(boolean downloadReady, boolean fileReady) {
        this.downloadReady = downloadReady;
        this.fileReady = fileReady;
    }

    public boolean isReadyForDownload() {
        return downloadReady;
    }

    public boolean isLocalFileReady() {
        return fileReady;
    }
}
