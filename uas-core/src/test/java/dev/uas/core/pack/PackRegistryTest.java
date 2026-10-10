package dev.uas.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.api.pack.PackHashes;
import dev.uas.api.pack.PackSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour of the pack registry, including under concurrent publication. */
class PackRegistryTest {

    private static PackSource pack(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id + ".zip");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return PackSource.of(id, file, PackHashes.sha1Hex(file), "test " + id);
    }

    @Test
    void resolvesRegisteredPacksAndReportsGeneration(@TempDir Path dir) throws IOException {
        PackRegistry registry = new PackRegistry();
        assertEquals(0, registry.generation());
        assertTrue(registry.isEmpty());

        registry.register(pack(dir, "alpha", "one"));
        long afterFirst = registry.generation();

        assertEquals(1, registry.size());
        assertFalse(registry.isEmpty());
        assertEquals(java.util.Set.of("alpha"), registry.knownPackIds());
        assertTrue(registry.resolve("alpha").isPresent());
        assertTrue(registry.resolve("missing").isEmpty());
        assertTrue(registry.resolve(null).isEmpty());
        assertEquals("test alpha", registry.resolve("alpha").orElseThrow().displayName());

        registry.register(pack(dir, "beta", "two"));
        // Every mutation advances the generation, which is how a pushed-pack
        // coordinator notices it must re-push without diffing contents.
        assertTrue(registry.generation() > afterFirst);
        assertEquals(2, registry.size());
    }

    @Test
    void replacingAPackKeepsTheOldOneValidForInFlightReaders(@TempDir Path dir) throws IOException {
        PackRegistry registry = new PackRegistry();
        PackSource first = pack(dir, "pack", "first-version");
        registry.register(first);

        PackSource heldByReader = registry.resolve("pack").orElseThrow();
        // A replacement is a *new* file (the compiler renames atomically), keeping the
        // same route id but a fresh artifact on disk.
        Path secondFile = dir.resolve("pack-v2.zip");
        Files.writeString(secondFile, "second-version", StandardCharsets.UTF_8);
        PackSource second = PackSource.of(
            "pack", secondFile, PackHashes.sha1Hex(secondFile), "test pack");
        registry.register(second);

        // The reader that already resolved the pack must be unaffected: no lock, no
        // torn state, and the object it holds still describes a real file.
        assertSame(first, heldByReader);
        assertTrue(heldByReader.verifyIntegrity());
        assertSame(second, registry.resolve("pack").orElseThrow());
        assertEquals(1, registry.replacements());
    }

    @Test
    void unregisterRemovesAndReports(@TempDir Path dir) throws IOException {
        PackRegistry registry = new PackRegistry();
        registry.register(pack(dir, "gone", "x"));

        assertTrue(registry.unregister("gone").isPresent());
        assertTrue(registry.unregister("gone").isEmpty());
        assertTrue(registry.isEmpty());
        assertFalse(registry.resolve("gone").isPresent());
    }

    @Test
    void refusesPacksThatCannotBeServed(@TempDir Path dir) throws IOException {
        PackRegistry registry = new PackRegistry();

        // A pack pointing at nothing would 500 on the first request; catching it at
        // registration turns a runtime failure into a startup failure.
        assertThrows(IllegalArgumentException.class, () -> registry.register(
            PackSource.of("nope", dir.resolve("absent.zip"), "0".repeat(40), "nope")));

        Path empty = dir.resolve("empty.zip");
        Files.createFile(empty);
        PackSource zero = PackSource.of("zero", empty, "0".repeat(40), "zero");
        assertThrows(IllegalArgumentException.class, () -> registry.register(zero));
    }

    @Test
    void doesNotVerifyByDefault(@TempDir Path dir) throws IOException {
        // The compiler produces the file in this process, so re-hashing 100 MiB at
        // registration would be pure cost.
        Path file = dir.resolve("liar.zip");
        Files.writeString(file, "actual");
        PackSource wrongHash = PackSource.of("liar", file, "a".repeat(40), "liar");
        PackRegistry registry = new PackRegistry();
        registry.register(wrongHash);
        assertTrue(registry.resolve("liar").isPresent());
    }

    @Test
    void verifiesOnRegisterWhenAsked(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("liar.zip");
        Files.writeString(file, "actual");
        PackSource wrongHash = PackSource.of("liar", file, "a".repeat(40), "liar");
        PackRegistry registry = new PackRegistry(true);
        assertThrows(IllegalArgumentException.class, () -> registry.register(wrongHash));
    }

    @Test
    void totalBytesAndDescribe(@TempDir Path dir) throws IOException {
        PackRegistry registry = new PackRegistry();
        registry.register(pack(dir, "a", "12345"));
        registry.register(pack(dir, "b", "1234567890"));

        assertEquals(15, registry.totalBytes());
        var report = registry.describe();
        assertEquals("2", report.get("packs"));
        assertEquals("15", report.get("totalBytes"));
        assertTrue(report.get("packIds").contains("a"));
    }

    @Test
    void concurrentReadersNeverSeeAnEmptyOrTornTable(@TempDir Path dir) throws Exception {
        PackRegistry registry = new PackRegistry();
        registry.register(pack(dir, "seed", "seed"));

        int readers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();

        try {
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 500; n++) {
                            // Once seeded, "seed" must be resolvable in every snapshot:
                            // a copy-on-write swap must never expose an intermediate map.
                            if (registry.resolve("seed").isEmpty()) {
                                failures.incrementAndGet();
                            }
                            registry.knownPackIds();
                            observations.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 200; n++) {
                        registry.register(pack(dir, "rotating", "v" + n));
                        registry.unregister("rotating");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers must finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get(), "no reader may observe a missing or broken entry");
        assertTrue(observations.get() >= readers * 500);
        assertTrue(registry.resolve("seed").isPresent());
    }
}
