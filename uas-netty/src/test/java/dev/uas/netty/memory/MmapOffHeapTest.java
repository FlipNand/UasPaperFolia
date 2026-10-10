package dev.uas.netty.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.api.pack.PackHashes;
import io.netty.buffer.ByteBuf;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that a memory-mapped asset source really is off-heap.
 *
 * <p>It is not enough that reading returns the right bytes -- a plain file read also
 * does that. The property that matters is that reading a 64 MiB file does not put
 * 64 MiB on the Java heap, so the test measures heap growth across a full read of
 * the mapping and fails if it is anywhere near the file size.
 */
@Timeout(120)
class MmapOffHeapTest {

    private static final int FILE_BYTES = 64 * 1024 * 1024;
    private static final int WINDOW = 256 * 1024;

    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        file = Files.createTempFile("uas-mmap", ".bin");
        Random random = new Random(4242);
        byte[] block = new byte[WINDOW];
        try (OutputStream out = Files.newOutputStream(file)) {
            int written = 0;
            while (written < FILE_BYTES) {
                random.nextBytes(block);
                out.write(block);
                written += block.length;
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("a mapped window returns the file's bytes and is a direct, off-heap buffer")
    void mappedWindowIsOffHeapAndCorrect() throws Exception {
        try (MmapByteBuf mapping = MmapByteBuf.map(file)) {
            assertEquals(FILE_BYTES, mapping.size());
            ByteBuf view = mapping.view();
            try {
                assertTrue(view.isDirect(), "a mapped buffer must be off-heap, not a heap copy");
                assertFalse(view.hasArray(), "an off-heap view must not expose a heap array");

                // Cross-check a few windows against an ordinary file read.
                byte[] expected = new byte[64 * 1024];
                try (var channel = java.nio.channels.FileChannel.open(file)) {
                    for (long offset : new long[] {0, 12345, FILE_BYTES / 2, FILE_BYTES - expected.length}) {
                        var buffer = java.nio.ByteBuffer.wrap(expected);
                        int read = channel.read(buffer, offset);
                        assertEquals(expected.length, read);
                        byte[] actual = new byte[expected.length];
                        view.getBytes((int) offset, actual);
                        assertArrayEquals(expected, actual, "window at offset " + offset);
                    }
                }
            } finally {
                view.release();
            }
        }
    }

    @Test
    @DisplayName("a windowed mapping of a region is correct and bounded")
    void windowedMapping() throws Exception {
        long offset = 10_000_000L;
        int length = 4096;
        try (MmapByteBuf mapping = MmapByteBuf.mapRange(file, offset, length)) {
            assertEquals(length, mapping.size());
            byte[] window = mapping.readBytes(0, length);

            byte[] expected = new byte[length];
            try (var channel = java.nio.channels.FileChannel.open(file)) {
                channel.read(java.nio.ByteBuffer.wrap(expected), offset);
            }
            assertArrayEquals(expected, window);
        }
        assertThrows(java.io.IOException.class, () -> MmapByteBuf.mapRange(file, FILE_BYTES - 10, 100));
    }

    @Test
    @DisplayName("CRC32C over the mapping matches the same digest computed by streaming")
    void crcMatchesStreaming() throws Exception {
        long streamed = PackHashes.crc32c(file);
        try (MmapByteBuf mapping = MmapByteBuf.map(file)) {
            assertEquals(streamed, mapping.crc32c());
        }
    }

    @Test
    @DisplayName("reading the whole 64 MiB mapping costs almost no heap")
    void readingWholeMappingDoesNotGrowTheHeap() throws Exception {
        // Warm up so class loading and the first-touch page faults are not measured.
        try (MmapByteBuf warmup = MmapByteBuf.map(file)) {
            warmup.crc32c();
        }
        settleHeap();

        long before = usedHeapBytes();
        byte[] sink = new byte[WINDOW];
        long bytesRead = 0;
        try (MmapByteBuf mapping = MmapByteBuf.map(file)) {
            ByteBuf view = mapping.view();
            try {
                for (int offset = 0; offset + WINDOW <= view.capacity(); offset += WINDOW) {
                    view.getBytes(offset, sink);
                    bytesRead += WINDOW;
                }
            } finally {
                view.release();
            }
        }
        long after = usedHeapBytes();
        long growth = after - before;

        assertEquals(FILE_BYTES, bytesRead, "the whole file must have been traversed");
        System.out.printf("mmap read of %d MiB grew the heap by %.2f MiB%n",
            FILE_BYTES / (1024 * 1024), growth / (1024.0 * 1024.0));
        // A single 64 KiB chunk is held at a time plus the sink; anything close to the
        // file size would mean the mapping or the read was copied onto the heap.
        assertTrue(growth < 16L * 1024 * 1024,
            "reading a " + (FILE_BYTES / (1024 * 1024)) + " MiB mapping grew the heap by "
                + growth + " bytes, which means it was copied onto the heap");
    }

    @Test
    @DisplayName("closing drops the mapping and reports whether the JVM unmapped it")
    void closeReleasesMapping() throws Exception {
        MmapByteBuf mapping = MmapByteBuf.mapRange(file, 0, 4096);
        assertTrue(mapping.isOpen());
        boolean unmapped = mapping.unmap();
        assertFalse(mapping.isOpen());
        assertThrows(IllegalStateException.class, mapping::view);
        // Both outcomes are acceptable: an explicit unmap, or release left to the
        // collector. What must not happen is a lie about which one occurred.
        System.out.println("explicit unmap succeeded: " + unmapped);
    }

    private static void settleHeap() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(60);
        }
    }

    private static long usedHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
