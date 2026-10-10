package dev.uas.api.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digest helpers that never load a whole file.
 *
 * <p>Every method streams through a fixed-size buffer, so hashing a 400 MB pack
 * costs a few kilobytes of heap regardless of the constrained-RAM profile.
 */
public final class PackHashes {

    /** Trade-off between syscall count and heap footprint; 64 KiB is deliberately small. */
    public static final int STREAM_BUFFER_BYTES = 64 * 1024;

    private PackHashes() {
    }

    public static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is a required JDK algorithm", e);
        }
    }

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    /** Streams a file through the given digest. The digest is mutated and returned. */
    public static MessageDigest digest(Path file, MessageDigest digest) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DigestInputStream in = new DigestInputStream(new java.io.BufferedInputStream(
                 raw, STREAM_BUFFER_BYTES), digest)) {
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            while (in.read(buffer) != -1) {
                // DigestInputStream updates the digest as a side effect of read().
            }
        }
        return digest;
    }

    public static String sha1Hex(Path file) throws IOException {
        return HexFormat.of().formatHex(digest(file, sha1Digest()).digest());
    }

    public static String sha256Hex(Path file) throws IOException {
        return HexFormat.of().formatHex(digest(file, sha256Digest()).digest());
    }

    /**
     * CRC32C of a file.
     *
     * <p>Used as the Bedrock pack content key, where a fast non-cryptographic
     * checksum is the convention and a full hash would be wasted work.
     */
    public static long crc32c(Path file) throws IOException {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        try (InputStream in = new java.io.BufferedInputStream(
            Files.newInputStream(file), STREAM_BUFFER_BYTES)) {
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    /** CRC32C of an in-memory array; used for manifest and JSON documents. */
    public static long crc32c(byte[] data) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(data);
        return crc.getValue();
    }
}
