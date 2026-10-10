package dev.uas.core.pack;

import dev.uas.api.pack.AssetSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the compiler needs, with the entry list canonicalised on construction.
 *
 * <p>Entries are sorted by path and duplicates are rejected here rather than deeper
 * in the zip writer. That buys two things: a zip whose byte layout does not depend on
 * the order a plugin happened to register things, so identical inputs produce an
 * identical archive, and a clear failure for the common "two generators wrote the
 * same file" bug instead of a silently duplicated entry.
 */
public record PackCompileRequest(
    String packId,
    Path output,
    Path scratchDirectory,
    List<AssetSource> entries,
    CompressionMode mode,
    Set<String> storedExtensions,
    boolean deterministicTimestamps,
    boolean writeIndex) {

    /** Refuses paths that would escape the archive or that zip readers reject. */
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;

    public PackCompileRequest {
        if (packId == null || packId.isBlank()) {
            throw new IllegalArgumentException("pack id must not be blank");
        }
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(scratchDirectory, "scratchDirectory");
        Objects.requireNonNull(entries, "entries");
        mode = mode == null ? CompressionMode.AUTO : mode;
        storedExtensions = storedExtensions == null
            ? CompressionMode.DEFAULT_STORED_EXTENSIONS
            : Set.copyOf(storedExtensions);

        List<AssetSource> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(AssetSource::path));
        Set<String> seen = new HashSet<>();
        for (AssetSource source : sorted) {
            String path = source.path();
            validatePath(path);
            if (!seen.add(path)) {
                throw new IllegalArgumentException("duplicate pack entry '" + path + "'");
            }
            long size = source.size();
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException(
                    "entry '" + path + "' declares " + size + " bytes, above the 2 GiB zip limit");
            }
        }
        entries = List.copyOf(sorted);
    }

    static void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("pack entry path must not be blank");
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new IllegalArgumentException("pack entry path must be relative: '" + path + "'");
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("pack entry path must not traverse: '" + path + "'");
        }
        if (path.contains("\\")) {
            throw new IllegalArgumentException("pack entry path must use '/' separators: '" + path + "'");
        }
    }

    public static Builder builder(String packId, Path output, Path scratchDirectory) {
        return new Builder(packId, output, scratchDirectory);
    }

    /** Total declared size of all entries, or {@code -1} when any size is unknown. */
    public long declaredTotalBytes() {
        long total = 0;
        for (AssetSource source : entries) {
            long size = source.size();
            if (size < 0) {
                return -1;
            }
            total += size;
        }
        return total;
    }

    public static final class Builder {
        private final String packId;
        private final Path output;
        private final Path scratchDirectory;
        private final List<AssetSource> entries = new ArrayList<>();
        private CompressionMode mode = CompressionMode.AUTO;
        private Set<String> storedExtensions = CompressionMode.DEFAULT_STORED_EXTENSIONS;
        private boolean deterministicTimestamps = true;
        private boolean writeIndex = true;

        private Builder(String packId, Path output, Path scratchDirectory) {
            this.packId = Objects.requireNonNull(packId, "packId");
            this.output = Objects.requireNonNull(output, "output");
            this.scratchDirectory = Objects.requireNonNull(scratchDirectory, "scratchDirectory");
        }

        public Builder entry(AssetSource source) {
            this.entries.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        public Builder entries(List<AssetSource> sources) {
            this.entries.clear();
            this.entries.addAll(sources);
            return this;
        }

        public Builder mode(CompressionMode value) {
            this.mode = value;
            return this;
        }

        public Builder storedExtensions(Set<String> value) {
            this.storedExtensions = value;
            return this;
        }

        public Builder deterministicTimestamps(boolean value) {
            this.deterministicTimestamps = value;
            return this;
        }

        public Builder writeIndex(boolean value) {
            this.writeIndex = value;
            return this;
        }

        public PackCompileRequest build() {
            return new PackCompileRequest(
                packId, output, scratchDirectory, entries, mode, storedExtensions,
                deterministicTimestamps, writeIndex);
        }
    }
}
