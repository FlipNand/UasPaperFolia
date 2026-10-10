package dev.uas.core.pack;

/**
 * One line of the compile-time asset index.
 *
 * <p>The index exists so a pack can be inspected after the fact without reopening
 * the archive, and so the streaming compiler can prove what it wrote without ever
 * holding the entry list in memory.
 */
public record AssetIndexEntry(
    String path,
    long size,
    long compressedSize,
    String method,
    long crc32) {

    public AssetIndexEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("index entry path must not be blank");
        }
    }

    /** Deflate ratio, or {@code 1.0} for stored entries and empty files. */
    public double ratio() {
        if (size <= 0 || compressedSize <= 0) {
            return 1.0d;
        }
        return (double) compressedSize / (double) size;
    }
}
