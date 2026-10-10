package dev.uas.core.pack;

import java.util.Locale;
import java.util.Set;

/** How each pack entry is stored in the archive. */
public enum CompressionMode {
    /**
     * Deflate everything except formats that are already compressed.
     *
     * <p>This is the default because re-deflating an OGG or PNG costs CPU and a
     * second streaming pass to measure it, and saves nothing. The extension list is
     * configurable because a pack may contain other pre-compressed payloads.
     */
    AUTO,
    /** Deflate every entry, including pre-compressed ones. */
    ALWAYS_DEFLATE,
    /** Store every entry uncompressed: fastest to build, largest on disk. */
    ALWAYS_STORE;

    /** Formats that are already compressed, so deflating them only wastes CPU. */
    public static final Set<String> DEFAULT_STORED_EXTENSIONS = Set.of(
        "ogg", "png", "jpg", "jpeg", "webp", "zip", "gz", "xz", "br", "mp3", "m4a", "ktx2");

    /** Whether an entry with this path should be stored rather than deflated. */
    public static boolean isPrecompressed(String path, Set<String> storedExtensions) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        return storedExtensions.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
