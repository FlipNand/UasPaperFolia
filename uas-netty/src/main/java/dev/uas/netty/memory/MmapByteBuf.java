package dev.uas.netty.memory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A zero-copy, off-heap view over a file.
 *
 * <p>Used for the paths where holding bytes on the Java heap is unacceptable:
 * hashing a large asset, probing an archive, or serving a window of a pack without
 * copying. {@link #view()} wraps the mapping in a Netty {@code ByteBuf} that reads
 * straight out of the page cache; the bytes are never on the heap, and the JVM's
 * heap accounting does not grow with the file size.
 *
 * <p><strong>Unmapping.</strong> The JVM gives no supported way to release a
 * {@code MappedByteBuffer} before GC decides to. {@link #close()} therefore makes a
 * best-effort unmapping attempt through {@code sun.misc.Unsafe.invokeCleaner} and
 * reports whether it worked; when it does not, the mapping is released when the
 * buffer becomes unreachable. This is why mmap is an optimisation the engine uses
 * for bounded windows rather than for whole multi-hundred-megabyte archives.
 */
public final class MmapByteBuf implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(MmapByteBuf.class.getName());

    private final Path file;
    private MappedByteBuffer buffer;

    private MmapByteBuf(Path file, MappedByteBuffer buffer) {
        this.file = file;
        this.buffer = buffer;
    }

    /** Maps the whole file read-only. */
    public static MmapByteBuf map(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        long size = java.nio.file.Files.size(file);
        if (size > Integer.MAX_VALUE) {
            throw new IOException("file " + file + " is " + size
                + " bytes; use mapRange for files larger than 2 GiB");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return new MmapByteBuf(file, mapped);
        }
    }

    /**
     * Maps a bounded window. This is how a large archive is treated as a sequence of
     * small mappings, so no single mapping grows with the file.
     */
    public static MmapByteBuf mapRange(Path file, long offset, long length) throws IOException {
        Objects.requireNonNull(file, "file");
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be >= 0");
        }
        if (length > Integer.MAX_VALUE) {
            throw new IOException("window of " + length + " bytes exceeds the 2 GiB mapping limit");
        }
        long size = java.nio.file.Files.size(file);
        if (offset + length > size) {
            throw new IOException("window " + offset + "+" + length + " exceeds file size " + size);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
            return new MmapByteBuf(file, mapped);
        }
    }

    public Path file() {
        return file;
    }

    public long size() {
        MappedByteBuffer current = buffer;
        return current == null ? 0 : current.capacity();
    }

    public boolean isOpen() {
        return buffer != null;
    }

    /** A Netty buffer over the whole mapping. Off-heap, zero-copy. */
    public ByteBuf view() {
        return view(0, Math.toIntExact(size()));
    }

    /** A Netty buffer over a sub-window. Off-heap, zero-copy. */
    public ByteBuf view(long offset, int length) {
        MappedByteBuffer current = requireOpen();
        if (offset < 0 || length < 0 || offset + length > current.capacity()) {
            throw new IndexOutOfBoundsException(
                "window " + offset + "+" + length + " exceeds mapping of " + current.capacity());
        }
        MappedByteBuffer slice = current.duplicate();
        slice.position(Math.toIntExact(offset));
        slice.limit(Math.toIntExact(offset + length));
        return Unpooled.wrappedBuffer(slice.slice());
    }

    /** Copies a window out. Used when a caller genuinely needs heap bytes. */
    public byte[] readBytes(long offset, int length) {
        ByteBuf view = view(offset, length);
        try {
            byte[] out = new byte[length];
            view.getBytes(0, out);
            return out;
        } finally {
            view.release();
        }
    }

    /** CRC32C over the whole mapping, in one pass, without touching the heap. */
    public long crc32c() {
        MappedByteBuffer current = requireOpen();
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        java.nio.ByteBuffer duplicate = current.duplicate();
        duplicate.position(0);
        byte[] chunk = new byte[64 * 1024];
        while (duplicate.hasRemaining()) {
            int count = Math.min(chunk.length, duplicate.remaining());
            duplicate.get(chunk, 0, count);
            crc.update(chunk, 0, count);
        }
        return crc.getValue();
    }

    private MappedByteBuffer requireOpen() {
        MappedByteBuffer current = buffer;
        if (current == null) {
            throw new IllegalStateException("mapping for " + file + " has been closed");
        }
        return current;
    }

    @Override
    public void close() {
        unmap();
    }

    /**
     * Drops the mapping.
     *
     * @return true when the mapping was actually unmapped, false when the JVM
     *     refused and release is left to the collector
     */
    public boolean unmap() {
        MappedByteBuffer current = buffer;
        buffer = null;
        if (current == null) {
            return true;
        }
        try {
            Method invokeCleaner = Class.forName("sun.misc.Unsafe").getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            invokeCleaner.invoke(unsafe, current);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.log(Level.FINEST,
                "could not explicitly unmap " + file + "; the mapping will be released by the collector", e);
            return false;
        }
    }

    @Override
    public String toString() {
        return "MmapByteBuf[" + file + ", " + size() + " bytes, "
            + (isOpen() ? "mapped" : "closed") + "]";
    }
}
