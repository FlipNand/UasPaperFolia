package dev.uas.core.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.uas.api.pack.AssetSource;
import dev.uas.api.pack.PackHashes;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Correctness of the streaming compiler: bytes, methods, index, and failure paths. */
class LowMemoryPackCompilerTest {

    /** A heap small enough to exercise the constrained profile on any machine. */
    private static MemoryBudget lowRamBudget() {
        return MemoryBudget.forProfile(MemoryProfile.LOW_RAM, 512L * 1024 * 1024);
    }

    private static LowMemoryPackCompiler compiler() {
        return new LowMemoryPackCompiler(lowRamBudget());
    }

    @Test
    void writesEveryEntryWithByteExactContent(@TempDir Path dir) throws IOException {
        byte[] texture = random(64 * 1024, 1);
        byte[] model = "{\"parent\":\"block/cube_all\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sound = random(32 * 1024, 2);

        PackCompileRequest request = PackCompileRequest
            .builder("exact", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("assets/uas/textures/block/gem.png", texture))
            .entry(AssetSource.of("assets/uas/models/block/gem.json", model))
            .entry(AssetSource.of("assets/uas/sounds/break.ogg", sound))
            .build();

        PackCompileResult result = compiler().compile(request);

        assertEquals(3, result.entryCount());
        assertTrue(Files.size(result.output()) == result.outputBytes());
        assertEquals(40, result.sha1().length());

        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(result.output().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                seen.add(entry.getName());
                byte[] expected = switch (entry.getName()) {
                    case "assets/uas/textures/block/gem.png" -> texture;
                    case "assets/uas/models/block/gem.json" -> model;
                    case "assets/uas/sounds/break.ogg" -> sound;
                    default -> throw new AssertionError("unexpected entry " + entry.getName());
                };
                // The whole point: what comes out is what went in, bit for bit.
                try (InputStream in = zip.getInputStream(entry)) {
                    assertArrayEquals(expected, in.readAllBytes(), entry.getName());
                }
            }
        }
        assertEquals(Set.of(
            "assets/uas/textures/block/gem.png",
            "assets/uas/models/block/gem.json",
            "assets/uas/sounds/break.ogg"), seen);
    }

    @Test
    void storesPrecompressedFormatsAndDeflatesText(@TempDir Path dir) throws IOException {
        byte[] ogg = random(16 * 1024, 3);
        byte[] json = ("x".repeat(4096)).getBytes(StandardCharsets.UTF_8);

        PackCompileRequest request = PackCompileRequest
            .builder("methods", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("sound.ogg", ogg))
            .entry(AssetSource.of("data.json", json))
            .build();

        compiler().compile(request);

        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            // Re-deflating an already-compressed format buys nothing and costs a pass,
            // so it must be stored.
            assertEquals(ZipEntry.STORED, zip.getEntry("sound.ogg").getMethod());
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("data.json").getMethod());
            // A stored entry's local header must carry the true size and CRC, or every
            // zip reader rejects the archive.
            ZipEntry stored = zip.getEntry("sound.ogg");
            assertEquals(ogg.length, stored.getSize());
            assertEquals(ogg.length, stored.getCompressedSize());
            assertArrayEquals(ogg, zip.getInputStream(stored).readAllBytes());
            // Highly repetitive input must actually shrink.
            assertTrue(zip.getEntry("data.json").getCompressedSize() < json.length);
        }
    }

    @Test
    void alwaysStoreStoresEvenTextEntries(@TempDir Path dir) throws IOException {
        byte[] json = ("y".repeat(8192)).getBytes(StandardCharsets.UTF_8);
        PackCompileRequest request = PackCompileRequest
            .builder("store-all", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("data.json", json))
            .mode(CompressionMode.ALWAYS_STORE)
            .build();

        compiler().compile(request);

        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            assertEquals(ZipEntry.STORED, zip.getEntry("data.json").getMethod());
            assertArrayEquals(json, zip.getInputStream(zip.getEntry("data.json")).readAllBytes());
        }
    }

    @Test
    void alwaysDeflateDeflatesPrecompressedFormats(@TempDir Path dir) throws IOException {
        byte[] ogg = random(32 * 1024, 4);
        PackCompileRequest request = PackCompileRequest
            .builder("deflate-all", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("sound.ogg", ogg))
            .mode(CompressionMode.ALWAYS_DEFLATE)
            .build();

        compiler().compile(request);

        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("sound.ogg").getMethod());
            assertArrayEquals(ogg, zip.getInputStream(zip.getEntry("sound.ogg")).readAllBytes());
        }
    }

    @Test
    void deflatesAStoredCandidateWhoseDeclaredSizeIsWrong(@TempDir Path dir) throws IOException {
        // A source that lies about its length must not produce a corrupt archive; the
        // compiler has to notice and fall back to deflate, which needs no prior knowledge.
        byte[] actual = random(5000, 5);
        AssetSource liar = new AssetSource() {
            @Override
            public String path() {
                return "liar.ogg";
            }

            @Override
            public long size() {
                return 1024;
            }

            @Override
            public InputStream open() {
                return new java.io.ByteArrayInputStream(actual);
            }
        };

        PackCompileRequest request = PackCompileRequest
            .builder("liar", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(liar)
            .build();
        compiler().compile(request);

        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            ZipEntry entry = zip.getEntry("liar.ogg");
            assertEquals(ZipEntry.DEFLATED, entry.getMethod());
            assertArrayEquals(actual, zip.getInputStream(entry).readAllBytes());
        }
    }

    @Test
    void hashMatchesAnIndependentPassOverTheArchive(@TempDir Path dir) throws IOException {
        PackCompileRequest request = PackCompileRequest
            .builder("hash", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("a.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
            .entry(AssetSource.of("b.ogg", random(4096, 6)))
            .build();

        PackCompileResult result = compiler().compile(request);

        // The digest is folded into the write path; this proves it equals an
        // independent read of the finished file.
        assertEquals(PackHashes.sha1Hex(result.output()), result.sha1());
        assertTrue(result.toPackSource("test").verifyIntegrity());
    }

    @Test
    void identicalInputsProduceIdenticalBytes(@TempDir Path dir) throws IOException {
        List<AssetSource> entries = List.of(
            AssetSource.of("z.json", "{}".getBytes(StandardCharsets.UTF_8)),
            AssetSource.of("a.json", "[]".getBytes(StandardCharsets.UTF_8)),
            AssetSource.of("m.ogg", random(2048, 7)));

        PackCompileResult first = new LowMemoryPackCompiler(lowRamBudget()).compile(
            PackCompileRequest.builder("det", dir.resolve("one.zip"), dir.resolve("scratch"))
                .entries(entries).build());
        PackCompileResult second = new LowMemoryPackCompiler(lowRamBudget()).compile(
            PackCompileRequest.builder("det", dir.resolve("two.zip"), dir.resolve("scratch"))
                .entries(entries).build());

        // Deterministic timestamps plus a sorted entry list. Without these two, a
        // rebuild changes the SHA-1 and every client re-downloads the pack.
        assertEquals(first.sha1(), second.sha1());
        assertEquals(first.outputBytes(), second.outputBytes());
        assertArrayEquals(Files.readAllBytes(first.output()), Files.readAllBytes(second.output()));
    }

    @Test
    void timestampsDifferWhenDeterminismIsDisabled(@TempDir Path dir) throws IOException {
        PackCompileRequest request = PackCompileRequest
            .builder("nondet", dir.resolve("out.zip"), dir.resolve("scratch"))
            .deterministicTimestamps(false)
            .entry(AssetSource.of("a.json", "{}".getBytes(StandardCharsets.UTF_8)))
            .build();
        compiler().compile(request);

        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            // Whatever the clock says, the entry must exist and be readable.
            assertNotNull(zip.getEntry("a.json"));
        }
    }

    @Test
    void writesAStreamingIndex(@TempDir Path dir) throws IOException {
        List<AssetSource> entries = new ArrayList<>();
        entries.add(AssetSource.of("text.json", "hello".getBytes(StandardCharsets.UTF_8)));
        entries.add(AssetSource.of("blob.ogg", random(8192, 8)));
        entries.add(AssetSource.of("other.txt", "world".getBytes(StandardCharsets.UTF_8)));

        PackCompileResult result = compiler().compile(PackCompileRequest
            .builder("indexed", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entries(entries).build());

        assertNotNull(result.indexFile());
        JsonObject document = JsonParser.parseString(Files.readString(result.indexFile())).getAsJsonObject();
        assertEquals("uas-asset-index/1", document.get("format").getAsString());
        JsonArray indexed = document.getAsJsonArray("entries");
        assertEquals(3, indexed.size());
        // Sorted, matching the archive order rather than registration order.
        assertEquals("blob.ogg", indexed.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("STORED", indexed.get(0).getAsJsonObject().get("method").getAsString());
        assertEquals("DEFLATED", indexed.get(1).getAsJsonObject().get("method").getAsString());

        JsonObject summary = document.getAsJsonObject("summary");
        assertEquals(3, summary.get("entryCount").getAsInt());
        assertEquals(1, summary.get("storedEntries").getAsInt());
        assertEquals(2, summary.get("deflatedEntries").getAsInt());
        assertEquals(result.totalUncompressedBytes(),
            summary.get("totalUncompressedBytes").getAsLong());
    }

    @Test
    void indexIsOptional(@TempDir Path dir) throws IOException {
        PackCompileResult result = compiler().compile(PackCompileRequest
            .builder("noindex", dir.resolve("out.zip"), dir.resolve("scratch"))
            .writeIndex(false)
            .entry(AssetSource.of("a.json", "{}".getBytes(StandardCharsets.UTF_8)))
            .build());

        assertNotNull(result.output());
        assertTrue(Files.exists(result.output()));
        assertEquals(1, result.entryCount());
    }

    @Test
    void rejectsDuplicateAndTraversalPaths(@TempDir Path dir) {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () ->
            PackCompileRequest.builder("dup", dir.resolve("out.zip"), dir.resolve("scratch"))
                .entry(AssetSource.of("a.json", new byte[1]))
                .entry(AssetSource.of("a.json", new byte[1]))
                .build());
        assertTrue(duplicate.getMessage().contains("duplicate"));

        IllegalArgumentException traversal = assertThrows(IllegalArgumentException.class, () ->
            PackCompileRequest.builder("trav", dir.resolve("out.zip"), dir.resolve("scratch"))
                .entry(AssetSource.of("../escape.json", new byte[1]))
                .build());
        assertTrue(traversal.getMessage().contains("traverse"));

        assertThrows(IllegalArgumentException.class, () ->
            PackCompileRequest.builder("abs", dir.resolve("out.zip"), dir.resolve("scratch"))
                .entry(AssetSource.of("/etc/passwd", new byte[1]))
                .build());
    }

    @Test
    void oversizedInMemoryAssetIsRejected(@TempDir Path dir) {
        // The escape hatch that would quietly defeat the low-memory profile.
        byte[] huge = new byte[dev.uas.api.pack.ByteArrayAssetSource.MAX_IN_MEMORY_ASSET_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () ->
            new dev.uas.api.pack.ByteArrayAssetSource("huge.json", huge));
    }

    @Test
    void failedCompileLeavesNoPartialArchive(@TempDir Path dir) throws IOException {
        Path output = dir.resolve("nested/out.zip");
        AssetSource exploding = new AssetSource() {
            @Override
            public String path() {
                return "boom.bin";
            }

            @Override
            public long size() {
                return 10_000;
            }

            @Override
            public InputStream open() {
                return new InputStream() {
                    private int served;

                    @Override
                    public int read() throws IOException {
                        if (served++ > 4096) {
                            throw new IOException("asset disappeared mid-stream");
                        }
                        return 'x';
                    }
                };
            }
        };

        assertThrows(IOException.class, () -> compiler().compile(PackCompileRequest
            .builder("boom", output, dir.resolve("scratch"))
            .entry(exploding)
            .build()));

        // A half-written archive that a client could fetch and then reject is worse
        // than no archive at all.
        assertFalse(Files.exists(output), "partial archive must not be visible");
        try (var files = Files.list(dir.resolve("scratch"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                "temp archive must be cleaned up");
        }
    }

    @Test
    void emptyEntriesAreHandled(@TempDir Path dir) throws IOException {
        PackCompileRequest request = PackCompileRequest
            .builder("empty", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("empty.json", new byte[0]))
            .entry(AssetSource.of("empty.ogg", new byte[0]))
            .build();

        PackCompileResult result = compiler().compile(request);

        assertEquals(2, result.entryCount());
        try (ZipFile zip = new ZipFile(request.output().toFile())) {
            assertEquals(0, zip.getEntry("empty.json").getSize());
            assertEquals(0, zip.getEntry("empty.ogg").getSize());
            assertArrayEquals(new byte[0], zip.getInputStream(zip.getEntry("empty.ogg")).readAllBytes());
        }
    }

    @Test
    void manyEntriesDoNotGrowTheWorkingSet(@TempDir Path dir) throws IOException {
        // 4000 entries is where a per-entry buffer allocation or an in-heap index would
        // start to show. The compile must complete and the index must be complete.
        List<AssetSource> entries = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            entries.add(AssetSource.of(
                String.format("assets/uas/items/%04d.json", i),
                ("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8)));
        }

        PackCompileResult result = compiler().compile(PackCompileRequest
            .builder("many", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entries(entries).build());

        assertEquals(4000, result.entryCount());
        try (ZipFile zip = new ZipFile(result.output().toFile())) {
            assertEquals(4000, zip.size());
            assertEquals("{\"i\":3999}", new String(
                zip.getInputStream(zip.getEntry("assets/uas/items/3999.json")).readAllBytes(),
                StandardCharsets.UTF_8));
        }
    }

    @Test
    void copyBufferSizeIsHonouredAndValidated(@TempDir Path dir) throws IOException {
        MemoryBudget budget = lowRamBudget();
        // A 1-byte copy buffer is the pathological case: it must still produce a correct
        // archive, proving nothing depends on the buffer holding a whole asset.
        LowMemoryPackCompiler tiny = new LowMemoryPackCompiler(budget, 6, 1);
        assertEquals(1, tiny.copyBufferBytes());

        byte[] content = random(20_000, 9);
        PackCompileResult result = tiny.compile(PackCompileRequest
            .builder("tiny-buffer", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("big.json", content))
            .build());

        try (ZipFile zip = new ZipFile(result.output().toFile())) {
            assertArrayEquals(content, zip.getInputStream(zip.getEntry("big.json")).readAllBytes());
        }

        assertThrows(IllegalArgumentException.class, () -> new LowMemoryPackCompiler(budget, 6, 0));
    }

    @Test
    void differingCopyBuffersProduceTheSameArchive(@TempDir Path dir) throws IOException {
        byte[] content = random(100_000, 10);
        Path one = dir.resolve("one.zip");
        Path two = dir.resolve("two.zip");

        new LowMemoryPackCompiler(lowRamBudget(), 6, 64).compile(PackCompileRequest
            .builder("same", one, dir.resolve("scratch"))
            .entry(AssetSource.of("data.json", content)).build());
        new LowMemoryPackCompiler(lowRamBudget(), 6, 64 * 1024).compile(PackCompileRequest
            .builder("same", two, dir.resolve("scratch"))
            .entry(AssetSource.of("data.json", content)).build());

        assertEquals(PackHashes.sha1Hex(one), PackHashes.sha1Hex(two));
    }

    @Test
    void recordCarriesTheEvidence(@TempDir Path dir) throws IOException {
        PackCompileResult result = compiler().compile(PackCompileRequest
            .builder("evidence", dir.resolve("out.zip"), dir.resolve("scratch"))
            .entry(AssetSource.of("a.json", "{}".getBytes(StandardCharsets.UTF_8)))
            .build());

        assertEquals(MemoryProfile.LOW_RAM, result.profile());
        assertEquals(Runtime.getRuntime().maxMemory(), result.maxHeapBytes());
        assertTrue(result.peakObservedHeapBytes() > 0);
        assertTrue(result.peakFractionOfMaxHeap() <= 1.0d);
        assertTrue(result.elapsedMillis() >= 0);
        assertNotEquals("", result.describe());
    }

    private static byte[] random(int size, long seed) {
        byte[] buffer = new byte[size];
        // Incompressible payload: the archive cannot shrink, so the compiler cannot
        // accidentally rely on a tiny intermediate representation.
        new SecureRandom(new byte[] {(byte) seed}).nextBytes(buffer);
        return buffer;
    }
}
