package ch.uemasa.syncmatica.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Content hashing matching the client: a file's hash is {@code UUID.nameUUIDFromBytes(MD5(bytes))}.
 * This is the value carried in placement metadata and used to verify completed transfers.
 */
public final class Checksum {

    private Checksum() {
    }

    public static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** @return the placement hash UUID for the given raw MD5 digest. */
    public static UUID fromDigest(byte[] md5Digest) {
        return UUID.nameUUIDFromBytes(md5Digest);
    }

    public static UUID ofBytes(byte[] bytes) {
        return fromDigest(md5().digest(bytes));
    }

    public static UUID ofStream(InputStream in) throws IOException {
        MessageDigest md = md5();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return fromDigest(md.digest());
    }
}
