package dev.uas.core.memory;

import java.util.Locale;

/**
 * How aggressively the engine trades CPU and disk for heap.
 *
 * <p>Selected automatically from the JVM's maximum heap, or forced by configuration
 * ({@code --uas-low-ram} / {@code low-memory-mode: true}). The threshold is on the
 * <em>heap</em>, not on physical RAM: what matters is how much room the engine has
 * to work with, and a server can be tuned either way independently of its machine.
 */
public enum MemoryProfile {

    /**
     * For instances at or below 5 GiB of heap. Nothing is buffered whole, caches are
     * off, and off-heap allocations are bounded tightly.
     */
    LOW_RAM(5L * 1024 * 1024 * 1024, false),

    /**
     * The default. Streaming everywhere, but small caches are kept because the
     * allocation savings outweigh the few tens of kilobytes they hold.
     */
    BALANCED(16L * 1024 * 1024 * 1024, true),

    /** For large instances: larger chunks and retained caches to cut allocator churn. */
    PERFORMANCE(Long.MAX_VALUE, true);

    /** At or below this heap size, the {@link #LOW_RAM} profile applies. */
    public static final long LOW_RAM_THRESHOLD_BYTES = 5L * 1024 * 1024 * 1024;

    private final long upperBoundExclusive;
    private final boolean cachesEnabled;

    MemoryProfile(long upperBoundExclusive, boolean cachesEnabled) {
        this.upperBoundExclusive = upperBoundExclusive;
        this.cachesEnabled = cachesEnabled;
    }

    /** True when the profile must avoid holding whole assets, caches or big chunks. */
    public boolean isConstrained() {
        return this == LOW_RAM;
    }

    public boolean cachesEnabled() {
        return cachesEnabled;
    }

    /** Bucket size for streaming copies. Small in low-RAM mode to bound the copy buffer. */
    public int copyBufferBytes() {
        return switch (this) {
            case LOW_RAM -> 16 * 1024;
            case BALANCED -> 64 * 1024;
            case PERFORMANCE -> 256 * 1024;
        };
    }

    /** Deflate level. Low-RAM mode compresses less to spend less CPU per byte. */
    public int compressionLevel() {
        return switch (this) {
            case LOW_RAM -> 3;
            case BALANCED -> 6;
            case PERFORMANCE -> 6;
        };
    }

    /** How many entries to hold in the compile-time index before flushing it to disk. */
    public int indexFlushInterval() {
        return switch (this) {
            case LOW_RAM -> 32;
            case BALANCED -> 256;
            case PERFORMANCE -> 1024;
        };
    }

    /**
     * Picks a profile from the JVM's maximum heap.
     *
     * @param maxHeapBytes {@code Runtime.maxMemory()}, or a value supplied by config
     * @param forcedLowRam when true, {@link #LOW_RAM} is returned regardless of size
     */
    public static MemoryProfile detect(long maxHeapBytes, boolean forcedLowRam) {
        if (forcedLowRam) {
            return LOW_RAM;
        }
        if (maxHeapBytes <= 0) {
            // An unknown heap is treated as constrained: the safe default is to buffer less.
            return LOW_RAM;
        }
        if (maxHeapBytes <= LOW_RAM_THRESHOLD_BYTES) {
            return LOW_RAM;
        }
        if (maxHeapBytes < BALANCED.upperBoundExclusive) {
            return BALANCED;
        }
        return PERFORMANCE;
    }

    /** Convenience for the common case: read the running JVM. */
    public static MemoryProfile detectRunningJvm() {
        return detect(Runtime.getRuntime().maxMemory(), false);
    }

    public static MemoryProfile parse(String value) {
        if (value == null) {
            return detectRunningJvm();
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LOW_RAM", "LOW-RAM", "LOWRAM", "LOW" -> LOW_RAM;
            case "BALANCED", "AUTO", "DEFAULT" -> BALANCED;
            case "PERFORMANCE", "PERF", "HIGH" -> PERFORMANCE;
            default -> throw new IllegalArgumentException(
                "unknown memory profile '" + value + "'; expected low_ram, balanced or performance");
        };
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
