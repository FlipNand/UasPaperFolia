package dev.uas.core.pack;

import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams the entry index to disk as it is produced.
 *
 * <p>Deliberately not a "collect a list, serialise at the end" writer. A pack with
 * tens of thousands of entries would otherwise build the entire index in heap, which
 * is precisely the pattern the constrained-RAM profile exists to avoid. Here each
 * entry is serialised the moment it is known and then becomes garbage.
 */
public final class AssetIndexWriter implements Closeable {

    private final Path file;
    private final Writer raw;
    private final JsonWriter writer;
    private boolean finished;
    private int count;
    private long totalUncompressedBytes;
    private long totalCompressedBytes;
    private int storedEntries;

    public AssetIndexWriter(Path file) throws IOException {
        this.file = file;
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.raw = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.writer = new JsonWriter(raw);
        // Readable output: the index is a diagnostic artifact, and an operator may
        // well open it in an editor. The writer itself is still streaming.
        this.writer.setIndent("  ");
        this.writer.beginObject();
        this.writer.name("format").value("uas-asset-index/1");
        this.writer.name("entries").beginArray();
    }

    public void add(AssetIndexEntry entry) throws IOException {
        if (finished) {
            throw new IllegalStateException("index already finished");
        }
        writer.beginObject();
        writer.name("path").value(entry.path());
        writer.name("size").value(entry.size());
        writer.name("compressedSize").value(entry.compressedSize());
        writer.name("method").value(entry.method());
        writer.name("crc32").value(Long.toUnsignedString(entry.crc32()));
        writer.endObject();

        count++;
        totalUncompressedBytes += Math.max(0, entry.size());
        totalCompressedBytes += Math.max(0, entry.compressedSize());
        if ("STORED".equals(entry.method())) {
            storedEntries++;
        }
    }

    /** Writes the summary and closes the document. Idempotent. */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        writer.endArray();
        writer.name("summary").beginObject();
        writer.name("entryCount").value(count);
        writer.name("storedEntries").value(storedEntries);
        writer.name("deflatedEntries").value(count - storedEntries);
        writer.name("totalUncompressedBytes").value(totalUncompressedBytes);
        writer.name("totalCompressedBytes").value(totalCompressedBytes);
        writer.endObject();
        writer.endObject();
        writer.flush();
        writer.close();
    }

    /**
     * Pushes buffered index bytes to disk without ending the document.
     *
     * <p>The compiler calls this periodically so that the pending portion of the
     * index document stays bounded by {@code indexFlushInterval()} entries. Without
     * it the {@link JsonWriter}'s own buffer would grow with the pack.
     */
    public void flush() throws IOException {
        if (finished) {
            return;
        }
        writer.flush();
        raw.flush();
    }

    public Path file() {
        return file;
    }

    public int count() {
        return count;
    }

    public long totalUncompressedBytes() {
        return totalUncompressedBytes;
    }

    public long totalCompressedBytes() {
        return totalCompressedBytes;
    }

    public int storedEntries() {
        return storedEntries;
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            finish();
        }
        raw.close();
    }
}
