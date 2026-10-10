package dev.uas.api.pack;

import java.io.IOException;
import java.io.InputStream;

/**
 * A readable asset.
 *
 * <p>The compiler only ever calls {@link #open()} and copies with a fixed buffer,
 * which is what makes zero-in-heap-buffering possible. {@link #readAll(int)} exists
 * but takes an explicit budget so that "read this small JSON file" cannot silently
 * become "read this 200 MB texture atlas".
 */
public interface AssetSource {

    /** Path inside the pack, e.g. {@code assets/uas/textures/block/lantern.png}. */
    String path();

    /** Size in bytes, or {@code -1} if unknown without reading. */
    long size();

    /** A fresh stream over the content. The caller closes it. */
    InputStream open() throws IOException;

    /** Reads the whole asset, refusing to allocate beyond {@code maxBytes}. */
    default byte[] readAll(int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
        }
        long declared = size();
        if (declared > maxBytes) {
            throw new IOException("asset '" + path() + "' is " + declared
                + " bytes which exceeds the " + maxBytes + "-byte budget");
        }
        try (InputStream in = new java.io.BufferedInputStream(open(), 8192)) {
            java.io.ByteArrayOutputStream sink =
                new java.io.ByteArrayOutputStream(declared > 0 ? (int) Math.min(declared, maxBytes) : 1024);
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("asset '" + path() + "' exceeded the "
                        + maxBytes + "-byte budget while reading");
                }
                sink.write(buffer, 0, read);
            }
            return sink.toByteArray();
        }
    }

    default String readUtf8(int maxBytes) throws IOException {
        return new String(readAll(maxBytes), java.nio.charset.StandardCharsets.UTF_8);
    }

    static AssetSource of(String path, byte[] content) {
        return new ByteArrayAssetSource(path, content);
    }

    static AssetSource of(java.nio.file.Path file, String pathInPack) {
        return new FileAssetSource(pathInPack, file);
    }
}
