package dev.uas.api.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A disk-backed asset.
 *
 * <p>This is the preferred source in the constrained-RAM profile: the file is
 * streamed straight from the filesystem into the compressor, so its contents never
 * occupy heap.
 */
public record FileAssetSource(String path, Path file) implements AssetSource {

    public FileAssetSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(file, "file");
        if (path.startsWith("/") || path.contains("../")) {
            throw new IllegalArgumentException("asset path must be relative and must not traverse: " + path);
        }
    }

    @Override
    public long size() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public InputStream open() throws IOException {
        return Files.newInputStream(file);
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    @Override
    public String toString() {
        return path + " <- " + file;
    }
}
