package dev.uas.netty.memory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit, bounded Netty memory configuration.
 *
 * <p>Netty's defaults are tuned for throughput on a big heap: 16 MiB chunks, two
 * arenas per core, and unbounded thread-local caches. On a 4 GB instance with eight
 * cores that can pin hundreds of megabytes of off-heap memory per direction before
 * a single asset is served.
 *
 * <p>These presets make the envelope explicit and small. The important invariant is
 * {@code chunkSize == pageSize << maxOrder}: it is checked eagerly, because Netty
 * silently rounds an inconsistent pair and the resulting mismatch is exactly the
 * kind of thing that shows up later as an unexplained memory graph.
 *
 * <p>{@link #applySystemProperties()} writes the same values as
 * {@code io.netty.allocator.*} system properties, which is what actually matters for
 * the server's own allocator (created before this plugin loads). The constructor
 * path exists for allocators this engine creates itself, and for tests.
 */
public record NettyAllocatorTuning(
    int chunkSizeKb,
    int pageSizeKb,
    int maxOrder,
    int numHeapArenas,
    int numDirectArenas,
    int tinyCacheSize,
    int smallCacheSize,
    int normalCacheSize,
    int maxCachedBufferCapacityKb,
    boolean preferDirect,
    boolean useCacheForAllThreads) {

    public NettyAllocatorTuning {
        if (chunkSizeKb <= 0 || pageSizeKb <= 0) {
            throw new IllegalArgumentException("chunk and page sizes must be positive");
        }
        if (Integer.bitCount(pageSizeKb) != 1) {
            throw new IllegalArgumentException("page size must be a power of two, got " + pageSizeKb + "KiB");
        }
        if (maxOrder < 0 || maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder must be in 0..14, got " + maxOrder);
        }
        if (pageSizeKb << maxOrder != chunkSizeKb) {
            throw new IllegalArgumentException(
                "inconsistent allocator geometry: pageSize " + pageSizeKb + "KiB << maxOrder " + maxOrder
                    + " = " + (pageSizeKb << maxOrder) + "KiB, but chunkSize is " + chunkSizeKb + "KiB");
        }
        // -1 means "let Netty decide" (2 x available processors); any other negative
        // value is a mistake.
        if (numHeapArenas < -1 || numDirectArenas < -1) {
            throw new IllegalArgumentException(
                "arena counts must be >= 0, or -1 for the Netty default; got heap="
                    + numHeapArenas + " direct=" + numDirectArenas);
        }
        if (tinyCacheSize < 0 || smallCacheSize < 0 || normalCacheSize < 0) {
            throw new IllegalArgumentException("cache sizes must be >= 0");
        }
        if (maxCachedBufferCapacityKb < 0) {
            throw new IllegalArgumentException("maxCachedBufferCapacity must be >= 0");
        }
    }

    /**
     * Default Netty geometry, stated explicitly so the delta is visible.
     *
     * <p>{-1} arena counts mean "2 x available processors", which is the default this
     * preset deliberately contrasts with.
     */
    public static NettyAllocatorTuning nettyDefault() {
        return new NettyAllocatorTuning(16 * 1024, 8, 11, -1, -1, 0, 256, 64, 32 * 1024, true, false);
    }

    /**
     * Constrained-RAM preset: 1 MiB chunks, two arenas, no thread-local caches.
     *
     * <p>Community-observed Netty default of 16 MiB per chunk x many arenas is the
     * single biggest avoidable off-heap cost on small instances. Caches are disabled
     * outright because their whole point is to trade memory for allocation speed, and
     * on a 4 GB heap the trade goes the wrong way.
     */
    public static NettyAllocatorTuning lowRam() {
        return new NettyAllocatorTuning(1024, 4, 8, 2, 2, 0, 0, 0, 16, false, false);
    }

    /** Middle ground: modest chunks, small per-thread caches retained. */
    public static NettyAllocatorTuning balanced() {
        return new NettyAllocatorTuning(2048, 8, 8, 4, 4, 0, 8, 4, 512, true, false);
    }

    public static NettyAllocatorTuning performance() {
        return nettyDefault();
    }

    /**
     * Heap-side steady-state ceiling: one chunk per arena, held only while buffers
     * are live. Returns {@code -1} when the arena count is left to Netty.
     *
     * <p>This is a bound on <em>idle</em> retention, not on the total of live buffers:
     * Netty grows the pool's committed chunks as long as callers keep buffers alive,
     * and no configuration makes that go away. What the chunk size controls is the
     * granularity of that growth and, with the caches disabled, how much is kept after
     * the buffers are released (nothing).
     */
    public long heapBudgetBytes() {
        return numHeapArenas < 0 ? -1L : (long) numHeapArenas * chunkSizeKb * 1024;
    }

    /** Off-heap equivalent of {@link #heapBudgetBytes()}. */
    public long directBudgetBytes() {
        return numDirectArenas < 0 ? -1L : (long) numDirectArenas * chunkSizeKb * 1024;
    }

    /** Worst-case slack above the bytes actually held live, across all arenas. */
    public long arenaSlackBytes() {
        long heapSlack = numHeapArenas < 0 ? 0 : (long) numHeapArenas * chunkSizeKb * 1024;
        long directSlack = numDirectArenas < 0 ? 0 : (long) numDirectArenas * chunkSizeKb * 1024;
        return heapSlack + directSlack;
    }

    /** The {@code io.netty.allocator.*} properties corresponding to this tuning. */
    public Map<String, String> systemProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("io.netty.allocator.type", "pooled");
        properties.put("io.netty.allocator.numHeapArenas", Integer.toString(numHeapArenas));
        properties.put("io.netty.allocator.numDirectArenas", Integer.toString(numDirectArenas));
        properties.put("io.netty.allocator.pageSize", Integer.toString(pageSizeKb * 1024));
        properties.put("io.netty.allocator.maxOrder", Integer.toString(maxOrder));
        properties.put("io.netty.allocator.tinyCacheSize", Integer.toString(tinyCacheSize));
        properties.put("io.netty.allocator.smallCacheSize", Integer.toString(smallCacheSize));
        properties.put("io.netty.allocator.normalCacheSize", Integer.toString(normalCacheSize));
        properties.put("io.netty.allocator.maxCachedBufferCapacity", Integer.toString(maxCachedBufferCapacityKb * 1024));
        properties.put("io.netty.allocator.useCacheForAllThreads", Boolean.toString(useCacheForAllThreads));
        return properties;
    }

    /**
     * Publishes the tuning as system properties.
     *
     * <p>Must run before the server creates its allocator to take effect there.
     * Applied later it still governs allocators created afterwards, so the engine
     * applies it on startup and reports what it found either way.
     */
    public void applySystemProperties() {
        systemProperties().forEach(System::setProperty);
    }

    /** Stamps this tuning onto an existing map, e.g. a Paper config-to-property bridge. */
    public void applyTo(Map<String, String> target) {
        target.putAll(systemProperties());
    }

    /**
     * Builds an allocator with exactly this geometry.
     *
     * <p>The explicit constructor is used instead of {@code new PooledByteBufAllocator(preferDirect)}
     * on purpose. The property-reading constructor depends on Netty's static initialisers, which
     * run on first class use -- so whether the properties take effect would depend on class-load
     * order. This constructor always produces the geometry asked for, which is what makes the
     * memory envelope an actual guarantee rather than a hope. Deprecated in Netty 4.2 in favour of
     * an incoming configuration API, but still the only deterministic path.
     *
     * <p>{@link #applySystemProperties()} is still applied first, so the server's own allocator
     * picks up the same geometry when it is created after this point.
     */
    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator newAllocator() {
        applySystemProperties();
        return new PooledByteBufAllocator(
            preferDirect,
            numHeapArenas,
            numDirectArenas,
            pageSizeKb * 1024,
            maxOrder,
            tinyCacheSize,
            smallCacheSize,
            normalCacheSize,
            useCacheForAllThreads);
    }

    /** The effective chunk size of an allocator, in bytes, or {@code -1} if not poolable. */
    public static long effectiveChunkSize(ByteBufAllocator allocator) {
        if (allocator instanceof PooledByteBufAllocator pooled) {
            return pooled.metric().chunkSize();
        }
        return -1L;
    }

    /** Verifies an allocator matches this tuning; returns a human-readable report. */
    public String verify(ByteBufAllocator allocator) {
        if (!(allocator instanceof PooledByteBufAllocator pooled)) {
            return "allocator is not pooled (" + allocator.getClass().getName() + "), tuning not applied";
        }
        PooledByteBufAllocatorMetricView view = PooledByteBufAllocatorMetricView.of(pooled);
        StringBuilder sb = new StringBuilder("allocator: chunk=").append(view.chunkSize())
            .append("B heapArenas=").append(view.numHeapArenas())
            .append(" directArenas=").append(view.numDirectArenas())
            .append(" smallCache=").append(view.smallCacheSize())
            .append(" normalCache=").append(view.normalCacheSize());
        if (view.chunkSize() != chunkSizeKb * 1024L) {
            sb.append(" [MISMATCH: expected ").append(chunkSizeKb * 1024).append(']');
        }
        return sb.toString();
    }

    /**
     * Flat view of the pool metrics, so callers and tests do not depend on Netty's metric API.
     *
     * <p>The per-arena cache accessors carry Netty's deprecation notices because 4.2 is replacing
     * the cache configuration surface; the values they report are still the real ones in use.
     */
    public record PooledByteBufAllocatorMetricView(
        int chunkSize,
        int numHeapArenas,
        int numDirectArenas,
        int smallCacheSize,
        int normalCacheSize,
        long usedHeapMemory,
        long usedDirectMemory) {

        @SuppressWarnings("deprecation")
        public static PooledByteBufAllocatorMetricView of(PooledByteBufAllocator allocator) {
            var metric = allocator.metric();
            return new PooledByteBufAllocatorMetricView(
                metric.chunkSize(),
                metric.numHeapArenas(),
                metric.numDirectArenas(),
                metric.smallCacheSize(),
                metric.normalCacheSize(),
                metric.usedHeapMemory(),
                metric.usedDirectMemory());
        }
    }
}
