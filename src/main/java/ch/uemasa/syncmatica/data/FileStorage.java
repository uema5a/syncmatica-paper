package ch.uemasa.syncmatica.data;

import ch.uemasa.syncmatica.util.Checksum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Content-addressed storage of {@code .litematic} blobs, named by content-hash UUID so identical
 * schematics are stored once. Downloads stream into a per-placement {@code .part} temp file so
 * concurrent identical uploads never share in-flight bytes.
 */
public final class FileStorage {

    private final Path folder;

    public FileStorage(Path folder) {
        this.folder = folder;
    }

    public Path getFolder() {
        return folder;
    }

    /** The final blob path {@code <hash>.litematic}; exists only once fully downloaded and hash-verified. */
    public Path getFile(ServerPlacement placement) {
        return folder.resolve(placement.getHash().toString() + ".litematic");
    }

    /** The per-placement temp path {@code <placementId>.part} an in-flight download streams into. */
    private Path getDownloadTarget(ServerPlacement placement) {
        return folder.resolve(placement.getId().toString() + ".part");
    }

    /** True if the final hash-named blob exists (a complete, verified file). */
    public boolean isFileReady(ServerPlacement placement) {
        return Files.isRegularFile(getFile(placement));
    }

    /** Re-hashes the on-disk blob and returns whether it matches the placement hash; false if missing/unreadable. */
    public boolean isHashValid(ServerPlacement placement) {
        try (InputStream in = Files.newInputStream(getFile(placement))) {
            return Checksum.ofStream(in).equals(placement.getHash());
        } catch (IOException e) {
            return false;
        }
    }

    public LocalLitematicState getLocalState(ServerPlacement placement, boolean downloading) {
        if (!isFileReady(placement)) {
            return downloading ? LocalLitematicState.DOWNLOADING_LITEMATIC : LocalLitematicState.NO_LOCAL_LITEMATIC;
        }
        return LocalLitematicState.LOCAL_LITEMATIC_PRESENT;
    }

    /** Creates the empty per-placement temp target {@code <placementId>.part}, replacing any stale one. */
    public Path createDownloadTarget(ServerPlacement placement) throws IOException {
        Files.createDirectories(folder);
        Path file = getDownloadTarget(placement);
        Files.deleteIfExists(file);
        Files.createFile(file);
        return file;
    }

    /**
     * Promotes the completed, hash-verified {@code .part} temp to the final {@code <hash>.litematic}.
     * Uses an atomic move where supported so a reader never sees a half-written blob.
     */
    public void finalizeDownload(ServerPlacement placement) throws IOException {
        Path temp = getDownloadTarget(placement);
        Path target = getFile(placement);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a stale per-placement {@code .part} temp after a failed download. */
    public void deleteDownloadTarget(ServerPlacement placement) throws IOException {
        Files.deleteIfExists(getDownloadTarget(placement));
    }

    public void delete(ServerPlacement placement) throws IOException {
        Files.deleteIfExists(getFile(placement));
    }
}
