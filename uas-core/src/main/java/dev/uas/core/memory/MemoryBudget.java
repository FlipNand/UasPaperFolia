package dev.uas.core.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The concrete ceilings the engine will operate within, derived once at startup and
 * reported so an operator can see what the engine decided.
 *
 * <p>Having these as numbers rather than as scattered {@code if (lowRam)} checks is
 * what makes the constrained-RAM behaviour testable: a test can assert the budget for
 * a 2 GiB heap without running a 2 GiB server.
 */
public record MemoryBudget(
    MemoryProfile profile,
    long maxHeapBytes,
    long compileHeapBudgetBytes,
    long httpHeapBudgetBytes,
    long directMemoryBudgetBytes,
    int maxAssetBytesReadIntoMemory) {

    /** Fraction of the heap the pack compiler may use for its own working set. */
    private static final double COMPILE_FRACTION = 0.15;

    /** Fraction reserved for Netty's pooled arenas and in-flight chunks. */
    private static final double NETTY_FRACTION = 0.10;

    /** Ceiling on any single "read this into memory" operation, e.g. a generated document. */
    private static final int LOW_RAM_DOCUMENT_LIMIT = 512 * 1024;

    private static final int BALANCED_DOCUMENT_LIMIT = 4 * 1024 * 1024;
    private static final int PERFORMANCE_DOCUMENT_LIMIT = 16 * 1024 * 1024;

    public MemoryBudget {
        if (maxHeapBytes <= 0) {
            throw new IllegalArgumentException("maxHeapBytes must be > 0, got " + maxHeapBytes);
        }
        if (compileHeapBudgetBytes > maxHeapBytes || directMemoryBudgetBytes > maxHeapBytes) {
            throw new IllegalArgumentException(
                "budget exceeds the heap: compile=" + compileHeapBudgetBytes
                    + " direct=" + directMemoryBudgetBytes + " heap=" + maxHeapBytes);
        }
    }

    public static MemoryBudget forProfile(MemoryProfile profile, long maxHeapBytes) {
        long compile = Math.max(8L * 1024 * 1024, (long) (maxHeapBytes * COMPILE_FRACTION));
        if (profile.isConstrained()) {
            compile = Math.min(compile, 64L * 1024 * 1024);
        }
        long netty = Math.max(4L * 1024 * 1024, (long) (maxHeapBytes * NETTY_FRACTION));
        long direct = Math.max(8L * 1024 * 1024, Math.min(netty, 256L * 1024 * 1024));
        int documentLimit = switch (profile) {
            case LOW_RAM -> LOW_RAM_DOCUMENT_LIMIT;
            case BALANCED -> BALANCED_DOCUMENT_LIMIT;
            case PERFORMANCE -> PERFORMANCE_DOCUMENT_LIMIT;
        };
        return new MemoryBudget(
            profile, maxHeapBytes, Math.min(compile, maxHeapBytes), netty, direct, documentLimit);
    }

    public static MemoryBudget detect(boolean forcedLowRam) {
        return forProfile(
            MemoryProfile.detect(Runtime.getRuntime().maxMemory(), forcedLowRam),
            Runtime.getRuntime().maxMemory());
    }

    /** Headroom the compiler must leave free for the rest of the server. */
    public long compileHeadroomBytes() {
        return Math.max(0, maxHeapBytes - compileHeapBudgetBytes);
    }

    /** True when a single asset of this size may be materialised on the heap. */
    public boolean mayReadIntoMemory(long assetBytes) {
        return assetBytes <= maxAssetBytesReadIntoMemory;
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("profile", profile.configName());
        report.put("maxHeapBytes", Long.toString(maxHeapBytes));
        report.put("maxHeapMiB", Long.toString(maxHeapBytes / (1024 * 1024)));
        report.put("compileHeapBudgetBytes", Long.toString(compileHeapBudgetBytes));
        report.put("httpHeapBudgetBytes", Long.toString(httpHeapBudgetBytes));
        report.put("directMemoryBudgetBytes", Long.toString(directMemoryBudgetBytes));
        report.put("maxAssetBytesReadIntoMemory", Integer.toString(maxAssetBytesReadIntoMemory));
        report.put("cachesEnabled", Boolean.toString(profile.cachesEnabled()));
        report.put("copyBufferBytes", Integer.toString(profile.copyBufferBytes()));
        return report;
    }

    @Override
    public String toString() {
        return "MemoryBudget[profile=" + profile.configName()
            + ", heap=" + (maxHeapBytes / (1024 * 1024)) + "MiB"
            + ", compile=" + (compileHeapBudgetBytes / (1024 * 1024)) + "MiB"
            + ", direct=" + (directMemoryBudgetBytes / (1024 * 1024)) + "MiB"
            + ", docLimit=" + (maxAssetBytesReadIntoMemory / 1024) + "KiB]";
    }
}
