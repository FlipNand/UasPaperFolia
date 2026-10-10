package dev.uas.netty;

import dev.uas.api.pack.PackHashes;
import dev.uas.api.pack.PackSource;
import dev.uas.netty.http.PackHttpRouter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

/**
 * Builds a real pack archive on disk.
 *
 * <p>The content is deterministic pseudo-random data, deliberately chosen over
 * compressible filler: a repeated byte pattern would let a bug in the range logic
 * pass by accident, because every byte would be identical.
 */
public final class PackTestFixture implements AutoCloseable {

    /** Larger than the 64 KiB stream chunk, and not a whole number of chunks. */
    public static final int DEFAULT_SIZE = (1 << 20) + 12345;

    private final Path directory;
    private final Path archive;
    private final String sha1;
    private final PackSource source;

    private PackTestFixture(Path directory, Path archive, String sha1, PackSource source) {
        this.directory = directory;
        this.archive = archive;
        this.sha1 = sha1;
        this.source = source;
    }

    public static PackTestFixture create(String packId, int size) throws IOException {
        Path directory = Files.createTempDirectory("uas-pack-fixture");
        Path archive = directory.resolve(packId + ".zip");
        Random random = new Random(0xC0FFEE);
        byte[] block = new byte[8192];
        try (OutputStream out = Files.newOutputStream(archive)) {
            int written = 0;
            while (written < size) {
                random.nextBytes(block);
                int chunk = Math.min(block.length, size - written);
                out.write(block, 0, chunk);
                written += chunk;
            }
        }
        String sha1 = PackHashes.sha1Hex(archive);
        PackSource source = PackSource.of(packId, archive, sha1, "UAS test pack " + packId);
        return new PackTestFixture(directory, archive, sha1, source);
    }

    public static PackTestFixture create(String packId) throws IOException {
        return create(packId, DEFAULT_SIZE);
    }

    public Path archive() {
        return archive;
    }

    public String sha1() {
        return sha1;
    }

    public PackSource source() {
        return source;
    }

    public byte[] readArchiveBytes() throws IOException {
        return Files.readAllBytes(archive);
    }

    public PackHttpRouter router() {
        return PackHttpRouter.of(Map.of(source.id(), source));
    }

    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort in a test fixture
                }
            });
        }
    }
}
