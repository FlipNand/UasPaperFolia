package dev.uas.netty.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the constrained-RAM allocator geometry is what it claims to be.
 *
 * <p>The point of the preset is that the memory ceiling is knowable. These tests
 * read the geometry back out of the live allocator and hold buffers to prove the
 * ceiling is enforced, rather than trusting that the numbers were passed through.
 */
@Timeout(60)
class NettyAllocatorTuningTest {

    @Test
    @DisplayName("the low-RAM preset has the documented geometry")
    void lowRamGeometry() {
        NettyAllocatorTuning tuning = NettyAllocatorTuning.lowRam();
        assertEquals(1024, tuning.chunkSizeKb());
        assertEquals(4, tuning.pageSizeKb());
        assertEquals(8, tuning.maxOrder());
        assertEquals(2, tuning.numHeapArenas());
        assertEquals(2, tuning.numDirectArenas());
        assertEquals(0, tuning.smallCacheSize());
        assertEquals(0, tuning.normalCacheSize());
        assertEquals(2L * 1024 * 1024, tuning.heapBudgetBytes());
        assertEquals(2L * 1024 * 1024, tuning.directBudgetBytes());

        // Compared with Netty's own default, which is what runs if nothing is tuned.
        assertEquals(16 * 1024, NettyAllocatorTuning.nettyDefault().chunkSizeKb());
        assertTrue(tuning.chunkSizeKb() < NettyAllocatorTuning.nettyDefault().chunkSizeKb());
    }

    @Test
    @DisplayName("inconsistent geometry is rejected rather than silently rounded")
    void geometryIsValidated() {
        // pageSize << maxOrder must equal chunkSize.
        assertThrows(IllegalArgumentException.class,
            () -> new NettyAllocatorTuning(1024, 4, 7, 2, 2, 0, 0, 0, 16, false, false));
        assertThrows(IllegalArgumentException.class,
            () -> new NettyAllocatorTuning(1024, 6, 8, 2, 2, 0, 0, 0, 16, false, false));
        assertThrows(IllegalArgumentException.class,
            () -> new NettyAllocatorTuning(1024, 3, 8, 2, 2, 0, 0, 0, 16, false, false));
        // Sanity: the preset itself is consistent.
        NettyAllocatorTuning.lowRam();
        NettyAllocatorTuning.balanced();
        NettyAllocatorTuning.nettyDefault();
    }

    @Test
    @DisplayName("a constructed allocator reports exactly the requested chunk size and arenas")
    void allocatorMatchesTuning() {
        NettyAllocatorTuning tuning = NettyAllocatorTuning.lowRam();
        PooledByteBufAllocator allocator = tuning.newAllocator();
        NettyAllocatorTuning.PooledByteBufAllocatorMetricView metric =
            NettyAllocatorTuning.PooledByteBufAllocatorMetricView.of(allocator);

        assertEquals(tuning.chunkSizeKb() * 1024, metric.chunkSize());
        assertEquals(tuning.numHeapArenas(), metric.numHeapArenas());
        assertEquals(tuning.numDirectArenas(), metric.numDirectArenas());
        assertEquals(0, metric.smallCacheSize());
        assertEquals(0, metric.normalCacheSize());
        assertTrue(tuning.verify(allocator).contains("chunk=" + (tuning.chunkSizeKb() * 1024) + "B"),
            tuning.verify(allocator));
        assertFalse(tuning.verify(allocator).contains("MISMATCH"), tuning.verify(allocator));
        assertEquals(tuning.chunkSizeKb() * 1024, NettyAllocatorTuning.effectiveChunkSize(allocator));
    }

    @Test
    @DisplayName("with caches disabled the arena holds nothing once buffers are released")
    void arenaReturnsToZeroWhenIdle() {
        NettyAllocatorTuning tuning = NettyAllocatorTuning.lowRam();
        PooledByteBufAllocator allocator = tuning.newAllocator();

        for (int round = 0; round < 20; round++) {
            List<ByteBuf> held = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                ByteBuf buffer = allocator.heapBuffer(64 * 1024, 64 * 1024);
                buffer.setByte(0, (byte) i);
                held.add(buffer);
            }
            long live = 64L * 64 * 1024;
            long used = NettyAllocatorTuning.PooledByteBufAllocatorMetricView.of(allocator).usedHeapMemory();
            // Live buffers plus at most one chunk of slack per arena. Note this is a
            // slack bound, not a cap on live data: Netty commits more chunks as long as
            // the caller keeps buffers alive, and no geometry setting changes that.
            assertTrue(used <= live + tuning.arenaSlackBytes(),
                "round " + round + ": arena at " + used + " bytes for " + live
                    + " live bytes, above the slack bound of " + tuning.arenaSlackBytes());
            held.forEach(ByteBuf::release);
        }

        long idle = NettyAllocatorTuning.PooledByteBufAllocatorMetricView.of(allocator).usedHeapMemory();
        assertEquals(0L, idle,
            "with small/normal caches disabled the arena must hold nothing when idle, but held " + idle);
    }

    @Test
    @DisplayName("an allocator left on Netty's defaults is reported, not silently accepted")
    void defaultAllocatorIsReported() {
        NettyAllocatorTuning tuning = NettyAllocatorTuning.lowRam();
        String report = tuning.verify(io.netty.buffer.ByteBufAllocator.DEFAULT);
        if (io.netty.buffer.ByteBufAllocator.DEFAULT instanceof PooledByteBufAllocator) {
            // The default was created before any of this ran, so it cannot match; the
            // point is that the mismatch is surfaced instead of assumed away.
            assertTrue(report.contains("allocator:"), report);
        }
    }

    @Test
    @DisplayName("the tuning maps onto io.netty.allocator.* properties")
    void systemProperties() {
        var properties = NettyAllocatorTuning.lowRam().systemProperties();
        assertEquals("pooled", properties.get("io.netty.allocator.type"));
        assertEquals("2", properties.get("io.netty.allocator.numHeapArenas"));
        assertEquals("2", properties.get("io.netty.allocator.numDirectArenas"));
        assertEquals("4096", properties.get("io.netty.allocator.pageSize"));
        assertEquals("8", properties.get("io.netty.allocator.maxOrder"));
        assertEquals("0", properties.get("io.netty.allocator.smallCacheSize"));
    }
}
