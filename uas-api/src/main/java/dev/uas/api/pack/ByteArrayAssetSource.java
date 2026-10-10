package dev.uas.api.pack;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * An in-memory asset.
 *
 * <p>Intended only for generated documents (the generated Bedrock JSON, the
 * {@code pack.mcmeta}) which are inherently small. The compiler logs a warning and
 * refuses to accept anything larger than
 * {@link #MAX_IN_MEMORY_ASSET_BYTES} so that a well-meaning caller cannot
 * accidentally defeat the low-memory profile by handing it a texture atlas.
 */
public record ByteArrayAssetSource(String path, byte[] content) implements AssetSource {

    /** A single generated document should never approach this. */
    public static final int MAX_IN_MEMORY_ASSET_BYTES = 4 * 1024 * 1024;

    public ByteArrayAssetSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_IN_MEMORY_ASSET_BYTES) {
            throw new IllegalArgumentException(
                "in-memory asset '" + path + "' is " + content.length + " bytes; the limit is "
                    + MAX_IN_MEMORY_ASSET_BYTES + ". Write it to disk and use FileAssetSource instead.");
        }
    }

    public static ByteArrayAssetSource utf8(String path, String content) {
        return new ByteArrayAssetSource(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public long size() {
        return content.length;
    }

    @Override
    public InputStream open() {
        return new ByteArrayInputStream(content);
    }
}
