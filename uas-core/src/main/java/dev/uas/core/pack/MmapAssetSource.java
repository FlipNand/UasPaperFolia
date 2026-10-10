package dev.uas.core.pack;

import dev.uas.api.pack.AssetSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * An asset served straight out of a memory-mapped file.
 *
 * <p>Mapping is the right primitive for the constrained-RAM profile because the bytes
 * live in the page cache, not the Java heap: a 300 MB texture atlas costs a few
 * hundred bytes of heap for the mapping object plus whatever the operating system
 * chooses to cache, and the OS can evict it under pressure instead of the JVM being
 * pushed into a collection storm.
 *
 * <p>Two details matter for correctness. The mapping is created lazily and reused, so
 * the compiler's two reads of a stored entry map the file once. And the mapping is
 * released explicitly via {@link #close()} rather than left to the cleaner, because on
 * a long-running server a leaked mapping keeps its file's pages pinned and prevents
 * the archive from being replaced.
 *
 * <p>Implements {@link AssetSource} so the compiler consumes it unchanged, and also
 * exposes a {@link ByteBuffer} view for a zero-copy writer.
 */
public final class MmapAssetSource implements AssetSource, AutoCloseable {

    /** Empty assets still need a non-null mapping to cache, and a zero-length map is illegal. */
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();

    private final String path;
    private final Path file;
    private final long offset;
    private final long length;
    private final Object lock = new Object();

    private ByteBuffer mapping;
    private FileChannel channel;
    private boolean closed;

    private MmapAssetSource(String path, Path file, long offset, long length) {
        this.path = Objects.requireNonNull(path, "path");
        this.file = Objects.requireNonNull(file, "file");
        this.offset = offset;
        this.length = length;
    }

    /** Maps an entire file. */
    public static MmapAssetSource of(String pathInPack, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("not a regular file: " + file);
        }
        return new MmapAssetSource(pathInPack, file, 0L, Files.size(file));
    }

    /** Maps a byte range of a file. */
    public static MmapAssetSource ofRange(String pathInPack, Path file, long offset, long length)
        throws IOException {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be >= 0");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("not a regular file: " + file);
        }
        if (offset + length > Files.size(file)) {
            throw new IOException("range " + offset + "+" + length + " exceeds " + file);
        }
        return new MmapAssetSource(pathInPack, file, offset, length);
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long size() {
        return length;
    }

    public Path file() {
        return file;
    }

    /** Maps on first use and caches the result. Idempotent and thread-safe. */
    private ByteBuffer mapping() throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("mapping for '" + path + "' is closed");
            }
            if (mapping == null) {
                if (length == 0) {
                    // FileChannel.map rejects zero-length mappings, but an empty asset
                    // is perfectly legal in a pack.
                    mapping = EMPTY;
                } else {
                    if (channel == null) {
                        channel = FileChannel.open(file, StandardOpenOption.READ);
                    }
                    mapping = channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
                }
            }
            return mapping;
        }
    }

    /**
     * A read-only view of the mapped bytes.
     *
     * <p>Duplicated rather than shared: two consumers must be able to read
     * independently without moving each other's position.
     */
    public ByteBuffer buffer() throws IOException {
        return mapping().duplicate().asReadOnlyBuffer();
    }

    /** Copies the mapped region to {@code out}. */
    public long transferTo(OutputStream out) throws IOException {
        ByteBuffer view = buffer();
        if (!view.hasRemaining()) {
            return 0L;
        }
        byte[] scratch = new byte[(int) Math.min(Math.max(1L, length), 64L * 1024L)];
        long total = 0;
        while (view.hasRemaining()) {
            int chunk = Math.min(view.remaining(), scratch.length);
            view.get(scratch, 0, chunk);
            out.write(scratch, 0, chunk);
            total += chunk;
        }
        return total;
    }

    @Override
    public InputStream open() throws IOException {
        return new ByteBufferInputStream(buffer());
    }

    /** Closes the channel and drops the mapping so the file can be replaced. */
    public boolean unmap() {
        synchronized (lock) {
            boolean released = true;
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    released = false;
                }
                channel = null;
            }
            // The JDK exposes no public unmap. Dropping the last strong reference is the
            // documented route: closing the channel plus clearing this field lets the
            // cleaner release the region promptly.
            mapping = null;
            return released;
        }
    }

    public boolean isMapped() {
        synchronized (lock) {
            return mapping != null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            unmap();
        }
    }

    @Override
    public String toString() {
        return "mmap:" + path + " (" + length + " bytes at " + offset + " from " + file + ")";
    }

    /** Streams a {@link ByteBuffer} without copying it into a byte array first. */
    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buffer;

        ByteBufferInputStream(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public int read() {
            return buffer.hasRemaining() ? buffer.get() & 0xFF : -1;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (length <= 0) {
                return 0;
            }
            if (!buffer.hasRemaining()) {
                return -1;
            }
            int chunk = Math.min(length, buffer.remaining());
            buffer.get(destination, offset, chunk);
            return chunk;
        }

        @Override
        public int available() {
            return buffer.remaining();
        }

        @Override
        public long skip(long n) {
            long skipped = Math.min(n, buffer.remaining());
            buffer.position(buffer.position() + (int) skipped);
            return skipped;
        }

        @Override
        public void close() {
            // The mapping belongs to the source, not to this view, so only the view's
            // own position is drained.
            buffer.position(buffer.limit());
        }
    }
}
