package dev.uas.core.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.uas.api.pack.PackHashes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the constrained-RAM guarantee the only way it can honestly be proved: by
 * compiling a pack much larger than the heap, in a JVM whose heap is actually small.
 *
 * <p>The compiler is run through its CLI in a <em>forked</em> process with
 * {@code -Xmx48m}. Running it in the test JVM would prove nothing, because the test
 * JVM has a gigabyte and an implementation that buffers the whole pack would sail
 * through. Here the input is ~112 MiB and the produced archive is ~96 MiB -- roughly
 * twice the heap -- so any implementation that materialised entries, the zip, or the
 * entry index in heap would die with an {@link OutOfMemoryError} rather than quietly
 * pass. The forked JVM is also the honest place to measure the peak, since
 * {@code Runtime.maxMemory()} inside it is the number an operator's server would have.
 */
class ForkedLowRamCompileTest {

    /** Deliberately below the 5 GiB threshold that selects the constrained profile. */
    private static final String HEAP = "-Xmx48m";

    /** One asset larger than the heap on its own, plus bulk to exceed it several times. */
    private static final int HUGE_ASSET_BYTES = 48 * 1024 * 1024;

    private static final int LARGE_ASSET_BYTES = 16 * 1024 * 1024;
    private static final int LARGE_ASSET_COUNT = 3;

    private static final int DOCUMENT_ASSET_BYTES = 8 * 1024 * 1024;
    private static final int DOCUMENT_ASSET_COUNT = 2;

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void compilesAPackTwiceTheHeapInside48m(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src");
        long expectedUncompressed = buildAssetTree(source);
        assertTrue(expectedUncompressed > 100L * 1024 * 1024,
            "fixture must dwarf the 48 MiB heap, was " + expectedUncompressed);

        Path outputOne = dir.resolve("pack-one.zip");
        Path reportOne = dir.resolve("report-one.json");
        int exitOne = runCompiler(dir, source, outputOne, reportOne);
        assertEquals(0, exitOne, "constrained-RAM compile must succeed; see logs above");

        JsonObject report = readReport(reportOne);
        long maxHeap = report.get("maxHeapBytes").getAsLong();
        long archiveBytes = report.get("outputBytes").getAsLong();
        long peakHeap = report.get("peakObservedHeapBytes").getAsLong();

        System.out.println("[uas] forked compile report: " + report);
        System.out.println("[uas] heap=" + mib(maxHeap) + "MiB, archive=" + mib(archiveBytes)
            + "MiB, peakObservedHeap=" + mib(peakHeap) + "MiB ("
            + String.format(java.util.Locale.ROOT, "%.1f%%", report.get("peakFractionOfMaxHeap").getAsDouble() * 100d)
            + ")");

        // The heap really was small, so the test is about the constraint and not luck.
        assertTrue(maxHeap <= 64L * 1024 * 1024, "fork must run under a small heap, was " + mib(maxHeap) + "MiB");
        assertEquals("low_ram", report.get("profile").getAsString(),
            "a 48 MiB heap must auto-select the constrained profile");

        // The headline result. The archive is about twice the heap, so it cannot have
        // been assembled anywhere but on disk, and no single entry could have been
        // materialised as a byte array.
        assertTrue(archiveBytes > maxHeap,
            "archive of " + mib(archiveBytes) + "MiB must exceed the " + mib(maxHeap)
                + "MiB heap, otherwise this proves nothing");

        // And it did so without ever exhausting that heap: an implementation that
        // buffered would have thrown OutOfMemoryError before writing a report at all.
        assertTrue(peakHeap < maxHeap,
            "peak observed heap " + mib(peakHeap) + "MiB must stay under the " + mib(maxHeap) + "MiB ceiling");
        assertTrue(report.get("peakFractionOfMaxHeap").getAsDouble() < 1.0d);

        // Six fixture files plus the pack.mcmeta the CLI synthesises, since the fixture
        // tree deliberately does not ship one.
        int fixtureEntries = 1 + LARGE_ASSET_COUNT + DOCUMENT_ASSET_COUNT;
        assertEquals(fixtureEntries + 1, report.get("entryCount").getAsInt());
        assertEquals(expectedUncompressed, HUGE_ASSET_BYTES
                + (long) LARGE_ASSET_BYTES * LARGE_ASSET_COUNT
                + (long) DOCUMENT_ASSET_BYTES * DOCUMENT_ASSET_COUNT);
        assertTrue(report.get("totalUncompressedBytes").getAsLong() >= expectedUncompressed,
            "the report must account for at least the bytes we wrote");
        // Four of the six fixture files are .ogg: the large and huge assets. Everything
        // else is JSON, including the generated mcmeta.
        assertEquals(1 + LARGE_ASSET_COUNT, report.get("storedEntries").getAsInt(),
            "every .ogg asset must take the stored path, including the 48 MiB one");

        // Verify the artifact the way a client would: against the source tree on disk.
        verifyArchive(source, outputOne, report.get("sha1").getAsString());

        // Repeat the compile in a second fork. Same inputs plus deterministic
        // timestamps must give a byte-identical archive, which is what lets clients
        // cache it instead of pulling 96 MiB again on every restart.
        Path outputTwo = dir.resolve("pack-two.zip");
        Path reportTwo = dir.resolve("report-two.json");
        assertEquals(0, runCompiler(dir, source, outputTwo, reportTwo));
        assertEquals(report.get("sha1").getAsString(),
            readReport(reportTwo).get("sha1").getAsString(),
            "two constrained compiles of the same input must produce the same SHA-1");
        assertEquals(Files.size(outputOne), Files.size(outputTwo));
    }

    /**
     * Creates the fixture, returning the total uncompressed size.
     *
     * <p>{@code .ogg} files are incompressible and land in the stored path (size and
     * CRC measured by a first pass); the {@code .json} files are repetitive and exercise
     * deflate. Both paths therefore run over inputs far bigger than the heap.
     */
    private static long buildAssetTree(Path root) throws IOException {
        Files.createDirectories(root.resolve("assets/uas/sounds"));
        Files.createDirectories(root.resolve("assets/uas/textures"));

        long total = 0;
        total += writeRandom(root.resolve("assets/uas/sounds/huge.ogg"), HUGE_ASSET_BYTES, 1);
        for (int i = 0; i < LARGE_ASSET_COUNT; i++) {
            total += writeRandom(
                root.resolve("assets/uas/sounds/large-" + i + ".ogg"), LARGE_ASSET_BYTES, 10 + i);
        }
        for (int i = 0; i < DOCUMENT_ASSET_COUNT; i++) {
            total += writeRepetitive(
                root.resolve("assets/uas/textures/bulk-" + i + ".json"), DOCUMENT_ASSET_BYTES);
        }
        return total;
    }

    private static long writeRandom(Path file, int bytes, long seed) throws IOException {
        SecureRandom random = new SecureRandom(new byte[] {(byte) seed, (byte) (seed >> 8)});
        byte[] block = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            int remaining = bytes;
            while (remaining > 0) {
                random.nextBytes(block);
                int chunk = Math.min(remaining, block.length);
                out.write(block, 0, chunk);
                remaining -= chunk;
            }
        }
        return bytes;
    }

    private static long writeRepetitive(Path file, int bytes) throws IOException {
        // Compressible content: deflate must actually stream it rather than buffer it.
        String line = "{\"generated\":true,\"padding\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}\n";
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(file)) {
            int remaining = bytes;
            while (remaining > 0) {
                int chunk = Math.min(remaining, payload.length);
                out.write(payload, 0, chunk);
                remaining -= chunk;
            }
        }
        return bytes;
    }

    /** Runs the CLI in a fresh JVM with a 48 MiB heap. */
    private static int runCompiler(Path dir, Path source, Path output, Path report)
        throws IOException, InterruptedException {

        String javaBinary = Path.of(System.getProperty("java.home"), "bin",
            isWindows() ? "java.exe" : "java").toString();

        List<String> command = new ArrayList<>(List.of(
            javaBinary,
            HEAP,
            // Small initial heap so the reported peak reflects work rather than
            // whatever the JVM reserved up front, and SerialGC so collection is
            // deterministic in a heap this small.
            "-Xms16m",
            "-XX:+UseSerialGC",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp", System.getProperty("java.class.path"),
            PackCompilerCli.class.getName(),
            "--source", source.toString(),
            "--output", output.toString(),
            "--scratch", dir.resolve("scratch").toString(),
            "--pack-id", "forked-low-ram",
            // Force the constrained recording path even if heap detection were
            // wrong: the report must still show low_ram.
            "--low-ram",
            "--report", report.toString()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String log;
        try (InputStream in = process.getInputStream()) {
            log = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        System.out.println("[uas] forked compiler exit=" + exit);
        System.out.println(log);
        if (exit != 0) {
            System.err.println("forked compiler failed with exit " + exit + ":\n" + log);
        }
        return exit;
    }

    private static JsonObject readReport(Path report) throws IOException {
        assertTrue(Files.exists(report), "compiler did not write a report");
        return JsonParser.parseString(Files.readString(report)).getAsJsonObject();
    }

    /**
     * Validates the archive independently of the compiler: entry set, entry sizes and
     * byte equality against the files that are still sitting on disk.
     */
    private static void verifyArchive(Path source, Path archive, String expectedSha1)
        throws IOException {

        assertNotNull(expectedSha1);
        assertEquals(PackHashes.sha1Hex(archive), expectedSha1,
            "the SHA-1 in the report must match an independent hash of the archive");

        Set<String> expectedPaths = new HashSet<>();
        try (var walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile)
                .forEach(path -> expectedPaths.add(
                    DirectoryScanner.toPackPath(source.relativize(path))));
        }
        // The CLI synthesises this one because the fixture tree does not ship it.
        expectedPaths.add("pack.mcmeta");

        Set<String> actualPaths = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                actualPaths.add(entry.getName());
                if (entry.getName().equals("pack.mcmeta")) {
                    assertTrue(entry.getSize() > 0, "generated pack.mcmeta must not be empty");
                    try (InputStream in = zip.getInputStream(entry)) {
                        var meta = JsonParser.parseString(
                            new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        assertTrue(meta.getAsJsonObject("pack").get("pack_format").getAsInt() >= 1,
                            "generated pack.mcmeta must carry a usable pack_format");
                    }
                    continue;
                }
                byte[] onDisk = Files.readAllBytes(source.resolve(entry.getName()));
                assertEquals(onDisk.length, entry.getSize(),
                    "declared size must match the source for " + entry.getName());
                try (InputStream in = zip.getInputStream(entry)) {
                    // Whole-file comparison, streamed by ZipFile rather than by us.
                    assertTrue(java.util.Arrays.equals(onDisk, in.readAllBytes()),
                        "content must round-trip byte for byte: " + entry.getName());
                }
            }
        }
        assertEquals(expectedPaths, actualPaths, "the archive must contain exactly the source tree");
    }

    private static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
