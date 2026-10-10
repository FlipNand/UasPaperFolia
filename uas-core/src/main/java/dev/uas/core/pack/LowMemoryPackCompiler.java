package dev.uas.core.pack;

import dev.uas.api.pack.AssetSource;
import dev.uas.api.pack.ByteArrayAssetSource;
import dev.uas.api.pack.PackHashes;
import dev.uas.core.memory.HeapSampler;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds resource packs without ever holding them in heap.
 *
 * <p>The invariant this class exists to preserve is simple to state and easy to
 * break: <em>the amount of heap used must not grow with the size of the pack.</em>
 * Everything follows from that. Asset bytes travel from {@link AssetSource#open()}
 * through one reused copy buffer into the deflater and out to a scratch file on
 * disk; the entry index is serialised as each entry completes; and the SHA-1 is
 * folded into the same write path via a digest stream, so hashing never re-reads the
 * archive.
 *
 * <p>The one place the invariant is awkward is {@link ZipEntry#setMethod(int) STORED}
 * entries. Zip requires a stored entry's size and CRC-32 in its local header, which
 * can only be known by reading the asset -- so the asset is read twice, once to
 * measure and once to copy. That is a CPU and I/O cost, not a memory cost, and it is
 * confined to formats that are already compressed ({@code .ogg}, {@code .png}), where
 * deflating would waste far more CPU than a second sequential read. When a source
 * declares a size that turns out to be wrong, the attempt is abandoned and the entry
 * is deflated instead, which is always safe.
 *
 * <p>Output is written to a sibling temp file and atomically renamed into place, so a
 * crash mid-compile never leaves a truncated archive that a client could download and
 * then reject.
 */
public final class LowMemoryPackCompiler {

    /** Zip stores the DOS epoch, so this is the canonical "no timestamp" value. */
    private static final long ZIP_EPOCH_MILLIS = 0L;

    /**
     * How often to read the heap while streaming a large entry.
     *
     * <p>A single 200 MB asset copied in 16 KiB blocks is ~12800 iterations; sampling
     * every 256 of them keeps the measurement honest without making the observer the
     * dominant cost.
     */
    private static final int HEAP_SAMPLE_EVERY_N_BLOCKS = 256;

    private final MemoryBudget budget;
    private final MemoryProfile profile;
    private final int compressionLevel;
    private final int copyBufferBytes;

    public LowMemoryPackCompiler(MemoryBudget budget) {
        this(budget, budget.profile().compressionLevel(), budget.profile().copyBufferBytes());
    }

    /**
     * @param compressionLevel deflate level; ignored for stored entries
     * @param copyBufferBytes  the single buffer reused for every entry copy. Test-only
     *                         hook: setting it absurdly low is how the low-RAM
     *                         guarantee gets probed at its weakest point.
     */
    public LowMemoryPackCompiler(MemoryBudget budget, int compressionLevel, int copyBufferBytes) {
        if (copyBufferBytes <= 0) {
            throw new IllegalArgumentException("copyBufferBytes must be > 0, got " + copyBufferBytes);
        }
        this.budget = Objects.requireNonNull(budget, "budget");
        this.profile = budget.profile();
        this.compressionLevel = compressionLevel;
        this.copyBufferBytes = copyBufferBytes;
    }

    /** A compiler configured for the running JVM's detected profile. */
    public static LowMemoryPackCompiler detected(boolean forcedLowRam) {
        return new LowMemoryPackCompiler(MemoryBudget.detect(forcedLowRam));
    }

    public MemoryBudget budget() {
        return budget;
    }

    public int copyBufferBytes() {
        return copyBufferBytes;
    }

    /**
     * Compiles the request's entries into a zip at {@link PackCompileRequest#output()}.
     *
     * <p>Blocking and single-threaded by design. Compression is CPU-bound and
     * sequential here, and parallelising it would reintroduce the buffering the whole
     * design avoids. Callers on a server thread should invoke this off-thread.
     *
     * @throws IOException if any asset cannot be read or the archive cannot be written
     */
    public PackCompileResult compile(PackCompileRequest request) throws IOException {
        Path output = request.output().toAbsolutePath().normalize();
        Path scratch = request.scratchDirectory().toAbsolutePath().normalize();
        Files.createDirectories(scratch);
        Path outputParent = output.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }

        Path tempArchive = Files.createTempFile(scratch, "uas-pack-", ".zip.part");
        Path indexFile = request.writeIndex() ? scratch.resolve(request.packId() + ".index.json") : null;

        HeapSampler heap = HeapSampler.start();
        MessageDigest sha1 = PackHashes.sha1Digest();

        long startedNanos = System.nanoTime();
        int entriesWritten;
        int storedEntries;
        long totalUncompressedBytes;

        AssetIndexWriter index = null;
        boolean committed = false;
        try {
            if (indexFile != null) {
                index = new AssetIndexWriter(indexFile);
            }

            // The digest sits *below* the zip stream so it sees exactly the bytes that
            // land on disk, central directory included. The recorded SHA-1 is therefore
            // the hash of the artifact as written -- the same value the client verifies
            // after downloading -- rather than a hash of the logical content.
            try (OutputStream fileOut = Files.newOutputStream(tempArchive);
                 CountingOutputStream counting =
                     new CountingOutputStream(new BufferedOutputStream(fileOut, 64 * 1024));
                 DigestOutputStream digest = new DigestOutputStream(counting, sha1);
                 ZipOutputStream zip = new ZipOutputStream(digest, StandardCharsets.UTF_8)) {

                zip.setLevel(compressionLevel);

                // One buffer for the entire compile. Allocating per entry would be
                // harmless for a ten-entry pack and fatal for a ten-thousand-entry one.
                byte[] copyBuffer = new byte[copyBufferBytes];
                CRC32 crc = new CRC32();

                for (AssetSource source : request.entries()) {
                    AssetIndexEntry written = writeEntry(zip, counting, source, request, copyBuffer, crc, heap);
                    if (index != null) {
                        index.add(written);
                        // The writer is itself buffered, so this bounds how much of the
                        // index document is ever in heap: one entry, not the whole list.
                        if (index.count() % profile.indexFlushInterval() == 0) {
                            index.flush();
                        }
                    }
                    heap.sample();
                }

                // Writing the central directory must happen before the rename, so it
                // stays inside the try block rather than waiting for close().
                zip.finish();
            }

            if (index != null) {
                index.finish();
            }
            heap.sample();

            storedEntries = index != null ? index.storedEntries() : -1;
            entriesWritten = index != null ? index.count() : request.entries().size();
            totalUncompressedBytes = index != null
                ? index.totalUncompressedBytes()
                : request.declaredTotalBytes();

            long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
            long outputBytes = Files.size(tempArchive);
            String sha1Hex = HexFormat.of().formatHex(sha1.digest());

            moveIntoPlace(tempArchive, output);
            committed = true;

            return new PackCompileResult(
                request.packId(), output, outputBytes, sha1Hex, entriesWritten,
                Math.max(0, storedEntries), Math.max(0, totalUncompressedBytes),
                elapsed, heap.peakBytes(), heap.maxHeapBytes(), profile, indexFile);

        } finally {
            if (index != null) {
                // finish() is idempotent, so this only closes the stream on the happy
                // path and completes the document on the failure path.
                index.close();
            }
            if (!committed) {
                // A failed compile must not leave something behind that looks like a
                // pack. The temp file lives in the scratch directory, which is ours.
                Files.deleteIfExists(tempArchive);
            }
        }
    }

    private AssetIndexEntry writeEntry(
        ZipOutputStream zip,
        CountingOutputStream counting,
        AssetSource source,
        PackCompileRequest request,
        byte[] copyBuffer,
        CRC32 crc,
        HeapSampler heap) throws IOException {

        String path = source.path();
        long declaredSize = source.size();
        boolean wantsStore = request.mode() == CompressionMode.ALWAYS_STORE
            || (request.mode() == CompressionMode.AUTO
                && CompressionMode.isPrecompressed(path, request.storedExtensions()));

        byte[] content = null;
        long measuredCrc = -1L;
        boolean store = false;

        if (wantsStore) {
            if (source instanceof ByteArrayAssetSource inMemory) {
                // Already on the heap; reading it back to measure would be pure waste.
                content = inMemory.content();
                crc.reset();
                crc.update(content, 0, content.length);
                measuredCrc = crc.getValue();
                store = true;
            } else if (declaredSize >= 0) {
                // Zip needs size and CRC up front for a stored entry, so the asset is
                // read once to measure. Bounded by the declaration, not by the content.
                long[] measured = measure(source, copyBuffer, crc, declaredSize);
                if (measured[0] == declaredSize) {
                    measuredCrc = measured[1];
                    store = true;
                }
                // Otherwise: the source lied about its size. Fall through to deflate,
                // which needs no prior knowledge and cannot corrupt the archive.
            }
        }

        ZipEntry entry = new ZipEntry(path);
        if (request.deterministicTimestamps()) {
            // Without this the archive differs on every build and no two servers can
            // agree on a SHA-1, which breaks the whole ETag story.
            entry.setTime(ZIP_EPOCH_MILLIS);
        }
        if (store) {
            long size = content != null ? content.length : declaredSize;
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(measuredCrc);
        } else {
            entry.setMethod(ZipEntry.DEFLATED);
        }

        long startOffset = counting.count();
        zip.putNextEntry(entry);
        long copied;
        long entryCrc;
        if (content != null) {
            zip.write(content, 0, content.length);
            copied = content.length;
            entryCrc = measuredCrc;
        } else if (store) {
            // Second read of a stored entry: this is the copy.
            copied = copy(source, zip, copyBuffer, heap, null);
            entryCrc = measuredCrc;
        } else {
            crc.reset();
            copied = copy(source, zip, copyBuffer, heap, crc);
            entryCrc = crc.getValue();
        }
        zip.closeEntry();
        // The local header, patch data and deflate framing are all included, which is
        // the number the client actually transfers.
        long compressedSize = counting.count() - startOffset;

        return new AssetIndexEntry(path, copied, compressedSize, store ? "STORED" : "DEFLATED", entryCrc);
    }

    /**
     * First pass over a stored entry: counts bytes and computes CRC-32.
     *
     * @return {@code [bytesRead, crc32]}
     */
    private long[] measure(AssetSource source, byte[] buffer, CRC32 crc, long declaredSize)
        throws IOException {
        crc.reset();
        long total = 0;
        try (InputStream in = new java.io.BufferedInputStream(
            source.open(), Math.min(buffer.length, 8192))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                crc.update(buffer, 0, read);
                if (total > declaredSize) {
                    // The size was wrong; stop reading now rather than finish a pass we
                    // are about to throw away.
                    return new long[] {total, crc.getValue()};
                }
            }
        }
        return new long[] {total, crc.getValue()};
    }

    /** Streams an entry into the zip, optionally folding it into a CRC. */
    private long copy(AssetSource source, OutputStream out, byte[] buffer, HeapSampler heap, CRC32 crc)
        throws IOException {
        long total = 0;
        long blocks = 0;
        try (InputStream in = new java.io.BufferedInputStream(
            source.open(), Math.min(buffer.length, 8192))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (crc != null) {
                    crc.update(buffer, 0, read);
                }
                total += read;
                if (++blocks % HEAP_SAMPLE_EVERY_N_BLOCKS == 0) {
                    heap.sample();
                }
            }
        }
        return total;
    }

    /**
     * Renames the finished temp archive over the output path.
     *
     * <p>An atomic move is attempted first; some filesystems (and cross-device
     * scratch directories) reject it, so a plain replace is the documented fallback.
     */
    private static void moveIntoPlace(Path temp, Path output) throws IOException {
        try {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Counts bytes written, so per-entry compressed sizes are known without seeking. */
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }
    }
}
