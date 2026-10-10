package dev.uas.core.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples used heap to observe the peak a phase actually reached.
 *
 * <p>{@code Runtime} exposes live heap, never a high-water mark, so a streaming
 * compiler that allocates briefly and releases cannot be validated by reading the
 * heap once at the end: by then the evidence is gone. Sampling inside the copy loop
 * and at entry boundaries turns "we never buffered the pack" into a number a test can
 * assert against.
 *
 * <p>Sampling is deliberately cheap and does not force a GC. Forcing a collection
 * would lower the reading and flatter the result; we want the honest live peak.
 */
public final class HeapSampler {

    private final AtomicLong peakBytes;
    private final AtomicLong samples;
    private final long maxHeapBytes;

    private HeapSampler(long maxHeapBytes) {
        this.maxHeapBytes = maxHeapBytes;
        this.peakBytes = new AtomicLong(currentHeapUsage());
        this.samples = new AtomicLong();
    }

    public static HeapSampler start() {
        return new HeapSampler(Runtime.getRuntime().maxMemory());
    }

    /** Live used heap right now, as total minus free. */
    public static long currentHeapUsage() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        return Math.max(0, total - free);
    }

    /** Records the current used heap and returns it. */
    public long sample() {
        long used = currentHeapUsage();
        samples.incrementAndGet();
        peakBytes.accumulateAndGet(used, Math::max);
        return used;
    }

    public long peakBytes() {
        return peakBytes.get();
    }

    public long sampleCount() {
        return samples.get();
    }

    public long maxHeapBytes() {
        return maxHeapBytes;
    }

    /** Peak as a fraction of the maximum heap; the headline low-RAM number. */
    public double peakFractionOfMaxHeap() {
        if (maxHeapBytes <= 0) {
            return 0d;
        }
        return (double) peakBytes.get() / (double) maxHeapBytes;
    }

    public String describe() {
        return "peak=" + (peakBytes.get() / (1024 * 1024)) + "MiB/"
            + (maxHeapBytes / (1024 * 1024)) + "MiB (" 
            + String.format(java.util.Locale.ROOT, "%.1f%%", peakFractionOfMaxHeap() * 100d)
            + ") over " + samples.get() + " samples";
    }
}
