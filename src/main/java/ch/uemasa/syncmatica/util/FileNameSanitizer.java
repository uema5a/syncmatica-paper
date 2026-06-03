package ch.uemasa.syncmatica.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Normalizes a received file name (port of upstream {@code ServerPlacement.normalizeFileName}):
 * reduces to the last path segment and replaces illegal/dangerous characters.
 */
public final class FileNameSanitizer {

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]|\\p{C}|\\p{M}");

    private FileNameSanitizer() {
    }

    public static String normalize(String fileName) {
        if (fileName == null) {
            return "_";
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            Path last = Paths.get(fileName.replace('\\', '/')).getFileName();
            return sanitize(last == null ? fileName : last.toString());
        }
        return sanitize(fileName);
    }

    private static String sanitize(String name) {
        return ILLEGAL.matcher(name).replaceAll("_").strip();
    }
}
