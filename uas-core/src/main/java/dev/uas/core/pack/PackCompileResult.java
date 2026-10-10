package dev.uas.core.pack;

import dev.uas.api.pack.PackSource;
import dev.uas.core.memory.MemoryProfile;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What a compile produced, including the evidence.
 *
 * <p>The peak heap fields are part of the result rather than a log line because the
 * constrained-RAM guarantee is the product feature: an operator (or a test) needs to
 * see the number the engine actually reached, not be told that it should have been
 * small.
 */
public record PackCompileResult(
    String packId,
    Path output,
    long outputBytes,
    String sha1,
    int entryCount,
    int storedEntries,
    long totalUncompressedBytes,
    long elapsedMillis,
    long peakObservedHeapBytes,
    long maxHeapBytes,
    MemoryProfile profile,
    Path indexFile) {

    public PackCompileResult {
        Objects.requireNonNull(packId, "packId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(sha1, "sha1");
        Objects.requireNonNull(profile, "profile");
    }

    /** Peak observed heap as a fraction of the maximum heap. */
    public double peakFractionOfMaxHeap() {
        if (maxHeapBytes <= 0) {
            return 0d;
        }
        return (double) peakObservedHeapBytes / (double) maxHeapBytes;
    }

    /** The artifact, ready to be registered with the streaming HTTP layer. */
    public PackSource toPackSource(String displayName) {
        return PackSource.of(packId, output, sha1, displayName);
    }

    public PackSource toPackSource() {
        return toPackSource(packId);
    }

    /** A one-line human summary, used by the CLI and the startup log. */
    public String describe() {
        return "pack '" + packId + "': " + entryCount + " entries ("
            + storedEntries + " stored), "
            + (totalUncompressedBytes / (1024 * 1024)) + "MiB uncompressed -> "
            + (outputBytes / (1024 * 1024)) + "MiB archive, sha1=" + sha1 + ", "
            + "peakHeap=" + (peakObservedHeapBytes / (1024 * 1024)) + "MiB of "
            + (maxHeapBytes / (1024 * 1024)) + "MiB ("
            + String.format(java.util.Locale.ROOT, "%.1f%%", peakFractionOfMaxHeap() * 100d) + "), "
            + "profile=" + profile.configName();
    }
}
