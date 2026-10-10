package dev.uas.api.pack;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A compiled pack artifact that can be streamed to a client.
 *
 * <p>Everything the HTTP layer needs is here, and nothing that would let it load
 * the pack into memory. {@link #sha1()} is computed exactly once, incrementally,
 * while the archive is written; {@link #etag()} derives from it so conditional
 * requests never touch the file.
 */
public interface PackSource {

    /** Stable route id, used in {@code /uas/pack/{id}}. */
    String id();

    /** The on-disk archive. Always a real file so it can be memory-mapped. */
    Path path();

    /** Size in bytes of the archive on disk. */
    long size();

    /** Lowercase hex SHA-1 of the archive, as sent to vanilla clients. */
    String sha1();

    /** Human-readable name, used in the resource pack prompt. */
    String displayName();

    /** Last-modified time in epoch millis. */
    default long lastModifiedMillis() {
        try {
            return java.nio.file.Files.getLastModifiedTime(path()).toMillis();
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    /** Strong ETag derived from the content hash. */
    default String etag() {
        return "\"" + sha1() + "\"";
    }

    /** SHA-1 as the raw 20-byte digest, which is the form vanilla clients expect. */
    default byte[] sha1Bytes() {
        String hex = sha1();
        if (hex.length() != 40) {
            throw new IllegalStateException("SHA-1 for pack '" + id() + "' is not 40 hex chars: " + hex);
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Verifies the recorded hash still matches the file on disk. */
    default boolean verifyIntegrity() {
        try {
            return size() == java.nio.file.Files.size(path())
                && sha1().equalsIgnoreCase(PackHashes.sha1Hex(path()));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    static PackSource of(String id, Path path, String sha1, String displayName) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha1, "sha1");
        long size;
        try {
            size = java.nio.file.Files.size(path);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("pack '" + id + "' is not readable at " + path, e);
        }
        return new PackSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Path path() {
                return path;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public String sha1() {
                return sha1;
            }

            @Override
            public String displayName() {
                return displayName;
            }
        };
    }
}
