package dev.uas.core.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mapped source must behave exactly like any other asset source. */
class MmapAssetSourceTest {

    @Test
    void mapsAndStreamsAWholeFile(@TempDir Path dir) throws IOException {
        byte[] content = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve("content.txt");
        Files.write(file, content);

        try (MmapAssetSource source = MmapAssetSource.of("content.txt", file)) {
            assertEquals("content.txt", source.path());
            assertEquals(content.length, source.size());
            assertFalse(source.isMapped(), "mapping must be lazy until first read");

            try (ByteArrayInputStream in = new ByteArrayInputStream(source.readAll(1024))) {
                assertArrayEquals(content, in.readAllBytes());
            }
            assertTrue(source.isMapped(), "first read must populate and cache the mapping");

            // A second read must reuse the mapping, not remap or leak a second region.
            assertArrayEquals(content, source.readAll(1024));
        }
    }

    @Test
    void twoConcurrentViewsDoNotSharePosition(@TempDir Path dir) throws IOException {
        byte[] content = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve("content.txt");
        Files.write(file, content);

        try (MmapAssetSource source = MmapAssetSource.of("content.txt", file)) {
            java.nio.ByteBuffer first = source.buffer();
            java.nio.ByteBuffer second = source.buffer();

            first.get();
            // Views must be independent: a consumer draining one cannot disturb another.
            assertEquals(0, second.position());
            assertEquals(content.length, first.remaining() + 1);
        }
    }

    @Test
    void mapsARange(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("content.bin");
        byte[] content = new byte[1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(file, content);

        try (MmapAssetSource source = MmapAssetSource.ofRange("slice.bin", file, 100, 50)) {
            assertEquals(50, source.size());
            byte[] read = source.readAll(128);
            assertEquals(50, read.length);
            assertEquals(100 & 0xFF, read[0] & 0xFF);
            assertEquals(149 & 0xFF, read[49] & 0xFF);
        }
    }

    @Test
    void handlesAnEmptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.bin");
        Files.createFile(file);

        try (MmapAssetSource source = MmapAssetSource.of("empty.bin", file)) {
            assertEquals(0, source.size());
            // FileChannel.map rejects a zero-length mapping, so this path must be
            // handled explicitly rather than surfacing as an obscure I/O error.
            assertArrayEquals(new byte[0], source.readAll(16));
        }
    }

    @Test
    void transferToWritesEveryByte(@TempDir Path dir) throws IOException {
        byte[] content = new byte[300_000];
        new java.util.Random(7).nextBytes(content);
        Path file = dir.resolve("big.bin");
        Files.write(file, content);

        try (MmapAssetSource source = MmapAssetSource.of("big.bin", file)) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            long written = source.transferTo(sink);
            assertEquals(content.length, written);
            assertArrayEquals(content, sink.toByteArray());
        }
    }

    @Test
    void rejectsBadRangesAndMissingFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("small.bin");
        Files.write(file, new byte[16]);

        assertThrows(IOException.class, () -> MmapAssetSource.of("missing.bin", dir.resolve("nope.bin")));
        assertThrows(IOException.class, () -> MmapAssetSource.ofRange("small.bin", file, 0, 100));
        assertThrows(IllegalArgumentException.class, () ->
            MmapAssetSource.ofRange("small.bin", file, -1, 4));
    }

    @Test
    void closingReleasesTheMappingAndRefusesFurtherReads(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("content.bin");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));

        MmapAssetSource source = MmapAssetSource.of("content.bin", file);
        source.readAll(16);
        assertTrue(source.isMapped());
        source.close();
        assertFalse(source.isMapped());

        // A closed source must fail loudly rather than read a released region, which
        // would be a crash on some platforms and stale data on others.
        assertThrows(IOException.class, () -> source.readAll(16));
        assertThrows(IOException.class, source::buffer);
        // Closing twice is a no-op.
        source.close();
    }

    @Test
    void mappedSourceIsAValidCompilerInput(@TempDir Path dir) throws IOException {
        // The end-to-end reason this class exists: a mapped source plugs into the
        // compiler unchanged, so a pack can be built from files that were never read
        // into a byte array.
        byte[] content = new byte[2 * 1024 * 1024];
        new java.util.Random(11).nextBytes(content);
        Path file = dir.resolve("huge.bin");
        Files.write(file, content);

        try (MmapAssetSource source = MmapAssetSource.of("huge.bin", file)) {
            PackCompileResult result = new LowMemoryPackCompiler(
                dev.uas.core.memory.MemoryBudget.forProfile(
                    dev.uas.core.memory.MemoryProfile.LOW_RAM, 256L * 1024 * 1024))
                .compile(PackCompileRequest
                    .builder("mmap", dir.resolve("out.zip"), dir.resolve("scratch"))
                    .entry(source)
                    .build());

            assertEquals(2 * 1024 * 1024, result.totalUncompressedBytes());
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(result.output().toFile())) {
                assertArrayEquals(content, zip.getInputStream(zip.getEntry("huge.bin")).readAllBytes());
            }
        }
    }
}
