package com.gs.ais.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 content hashing helpers used for attachment de-duplication.
 *
 * <p>Uses only JDK standard library classes ({@link MessageDigest},
 * {@link DigestInputStream}) so it works under GraalVM Native Image without
 * AWT/ImageIO. Digests are returned as lowercase hex strings.
 */
public final class ContentHashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentHashUtil() {
    }

    /** SHA-256 hex digest of an in-memory byte array. */
    public static String sha256Hex(byte[] data) {
        return toHex(digest(data));
    }

    /**
     * SHA-256 hex digest of a file, streamed so large files are not loaded into
     * memory.
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, md)) {
            byte[] buffer = new byte[8192];
            while (digestIn.read(buffer) != -1) {
                // digest stream updates the digest as bytes are read
            }
        }
        return toHex(md.digest());
    }

    private static byte[] digest(byte[] data) {
        return newSha256().digest(data);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
