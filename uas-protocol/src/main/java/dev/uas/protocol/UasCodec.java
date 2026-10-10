package dev.uas.protocol;

import dev.uas.api.NamespacedKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The UAS binary codec: a single growable heap buffer usable in both directions.
 *
 * <p>Wire format is Minecraft-compatible where it matters (VarInt, length-prefixed
 * UTF-8, UUID-as-two-longs) so the same bytes are valid on the Java plugin-message
 * channel and on the framed channel the Geyser bridge reads.
 *
 * <p><strong>Bounded by construction.</strong> Every length prefix is checked
 * against an explicit limit before any allocation happens, so a hostile or
 * truncated payload can never make the server allocate an arbitrary amount of
 * memory. Control packets are small by nature, which is why a heap buffer is the
 * right choice here even in the constrained-RAM profile -- the discipline is that
 * this codec is <em>never</em> used for asset payloads.
 */
public final class UasCodec {

    /** Matches Minecraft's VarInt string limit (32767 UTF-16 chars, at most 3 bytes each). */
    public static final int MAX_STRING_BYTES = 32767 * 3;

    /** Default cap for byte arrays carried in a control packet. */
    public static final int DEFAULT_MAX_ARRAY_BYTES = 1 << 20;

    private static final int DEFAULT_CAPACITY = 64;
    private static final int MAX_VARINT_BYTES = 5;
    private static final int MAX_VARLONG_BYTES = 10;

    private byte[] buffer;
    private int readIndex;
    private int writeIndex;
    private int maxStringBytes = MAX_STRING_BYTES;
    private int maxArrayBytes = DEFAULT_MAX_ARRAY_BYTES;

    private UasCodec(byte[] buffer, int readIndex, int writeIndex) {
        this.buffer = buffer;
        this.readIndex = readIndex;
        this.writeIndex = writeIndex;
    }

    public static UasCodec encoder() {
        return new UasCodec(new byte[DEFAULT_CAPACITY], 0, 0);
    }

    public static UasCodec encoder(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initial capacity must be >= 0, got " + initialCapacity);
        }
        return new UasCodec(new byte[Math.max(DEFAULT_CAPACITY, initialCapacity)], 0, 0);
    }

    public static UasCodec decoder(byte[] data) {
        return decoder(data, 0, data == null ? 0 : data.length);
    }

    public static UasCodec decoder(byte[] data, int offset, int length) {
        if (data == null) {
            throw new UasProtocolException("cannot decode from a null buffer");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new UasProtocolException(
                "invalid decode window offset=" + offset + " length=" + length + " capacity=" + data.length);
        }
        return new UasCodec(data, offset, offset + length);
    }

    /** Tightens the per-codec limits below the defaults; used when untrusted peers talk to us. */
    public UasCodec withLimits(int stringBytes, int arrayBytes) {
        if (stringBytes <= 0 || arrayBytes <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxStringBytes = stringBytes;
        this.maxArrayBytes = arrayBytes;
        return this;
    }

    public int readerIndex() {
        return readIndex;
    }

    public int writerIndex() {
        return writeIndex;
    }

    public int readableBytes() {
        return writeIndex - readIndex;
    }

    public boolean isReadable() {
        return readIndex < writeIndex;
    }

    /** Rewinds the read cursor to the start; the buffer contents are untouched. */
    public UasCodec resetRead() {
        this.readIndex = 0;
        return this;
    }

    private void ensureWritable(int additional) {
        int required = writeIndex + additional;
        if (required <= buffer.length) {
            return;
        }
        int grown = buffer.length;
        while (grown < required) {
            grown = grown >= (1 << 24) ? grown + (1 << 24) : grown * 2;
        }
        byte[] replacement = new byte[grown];
        System.arraycopy(buffer, 0, replacement, 0, writeIndex);
        this.buffer = replacement;
    }

    private void requireReadable(int count, String field) {
        if (count < 0) {
            throw new UasProtocolException("negative read length for " + field + ": " + count);
        }
        if (readableBytes() < count) {
            throw UasProtocolException.truncated(field, count, readableBytes());
        }
    }

    // ---------------------------------------------------------------- VarInt

    public UasCodec writeVarInt(int value) {
        ensureWritable(MAX_VARINT_BYTES);
        int remaining = value;
        do {
            int bits = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                bits |= 0x80;
            }
            buffer[writeIndex++] = (byte) bits;
        } while (remaining != 0);
        return this;
    }

    public int readVarInt() {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            if (readIndex >= writeIndex) {
                throw UasProtocolException.truncated("VarInt", i + 1, i);
            }
            byte current = buffer[readIndex++];
            result |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new UasProtocolException("VarInt exceeds " + MAX_VARINT_BYTES + " bytes");
    }

    public UasCodec writeVarLong(long value) {
        ensureWritable(MAX_VARLONG_BYTES);
        long remaining = value;
        do {
            long bits = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                bits |= 0x80;
            }
            buffer[writeIndex++] = (byte) bits;
        } while (remaining != 0);
        return this;
    }

    public long readVarLong() {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < MAX_VARLONG_BYTES; i++) {
            if (readIndex >= writeIndex) {
                throw UasProtocolException.truncated("VarLong", i + 1, i);
            }
            byte current = buffer[readIndex++];
            result |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new UasProtocolException("VarLong exceeds " + MAX_VARLONG_BYTES + " bytes");
    }

    /** Number of bytes {@code writeVarInt} would produce; used for size budgeting. */
    public static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    // ------------------------------------------------------------- primitives

    public UasCodec writeBoolean(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public UasCodec writeByte(int value) {
        ensureWritable(1);
        buffer[writeIndex++] = (byte) value;
        return this;
    }

    public byte readByte() {
        requireReadable(1, "byte");
        return buffer[readIndex++];
    }

    public UasCodec writeShort(int value) {
        ensureWritable(2);
        buffer[writeIndex++] = (byte) (value >>> 8);
        buffer[writeIndex++] = (byte) value;
        return this;
    }

    public short readShort() {
        requireReadable(2, "short");
        return (short) ((buffer[readIndex++] & 0xFF) << 8 | (buffer[readIndex++] & 0xFF));
    }

    public UasCodec writeInt(int value) {
        ensureWritable(4);
        buffer[writeIndex++] = (byte) (value >>> 24);
        buffer[writeIndex++] = (byte) (value >>> 16);
        buffer[writeIndex++] = (byte) (value >>> 8);
        buffer[writeIndex++] = (byte) value;
        return this;
    }

    public int readInt() {
        requireReadable(4, "int");
        return (buffer[readIndex++] & 0xFF) << 24
            | (buffer[readIndex++] & 0xFF) << 16
            | (buffer[readIndex++] & 0xFF) << 8
            | (buffer[readIndex++] & 0xFF);
    }

    public UasCodec writeLong(long value) {
        writeInt((int) (value >>> 32));
        writeInt((int) value);
        return this;
    }

    public long readLong() {
        long high = readInt() & 0xFFFFFFFFL;
        long low = readInt() & 0xFFFFFFFFL;
        return high << 32 | low;
    }

    public UasCodec writeFloat(float value) {
        return writeInt(Float.floatToRawIntBits(value));
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public UasCodec writeDouble(double value) {
        return writeLong(Double.doubleToRawLongBits(value));
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public UasCodec writeUuid(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
        return this;
    }

    public UUID readUuid() {
        long most = readLong();
        long least = readLong();
        return new UUID(most, least);
    }

    // ------------------------------------------------------------- composites

    public UasCodec writeString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxStringBytes) {
            throw new UasProtocolException(
                "string of " + encoded.length + " bytes exceeds the " + maxStringBytes + "-byte limit");
        }
        writeVarInt(encoded.length);
        writeRaw(encoded);
        return this;
    }

    public String readString() {
        int length = readVarInt();
        if (length < 0) {
            throw new UasProtocolException("negative string length: " + length);
        }
        if (length > maxStringBytes) {
            throw UasProtocolException.oversized("string", length, maxStringBytes);
        }
        requireReadable(length, "string body");
        String value = new String(buffer, readIndex, length, StandardCharsets.UTF_8);
        readIndex += length;
        return value;
    }

    /** Writes bytes with no length prefix. The caller is responsible for framing them. */
    public UasCodec writeRawBytes(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("byte array must not be null");
        }
        writeRaw(value);
        return this;
    }

    /** Copies exactly {@code length} bytes out of the read cursor. */
    public byte[] readRawBytes(int length) {
        requireReadable(length, "raw bytes");
        byte[] value = new byte[length];
        System.arraycopy(buffer, readIndex, value, 0, length);
        readIndex += length;
        return value;
    }

    public UasCodec writeBytes(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("byte array must not be null");
        }
        if (value.length > maxArrayBytes) {
            throw new UasProtocolException(
                "byte array of " + value.length + " exceeds the " + maxArrayBytes + "-byte limit");
        }
        writeVarInt(value.length);
        writeRaw(value);
        return this;
    }

    public byte[] readBytes() {
        int length = readVarInt();
        if (length < 0) {
            throw new UasProtocolException("negative byte array length: " + length);
        }
        if (length > maxArrayBytes) {
            throw UasProtocolException.oversized("byte array", length, maxArrayBytes);
        }
        requireReadable(length, "byte array body");
        byte[] value = new byte[length];
        System.arraycopy(buffer, readIndex, value, 0, length);
        readIndex += length;
        return value;
    }

    public UasCodec writeNamespacedKey(NamespacedKey key) {
        return writeString(key.toString());
    }

    public NamespacedKey readNamespacedKey() {
        return NamespacedKey.parse(readString());
    }

    public <E extends Enum<E>> UasCodec writeEnum(E value) {
        if (value == null) {
            throw new IllegalArgumentException("enum value must not be null");
        }
        writeVarInt(value.ordinal());
        return this;
    }

    public <E extends Enum<E>> E readEnum(Class<E> type) {
        int ordinal = readVarInt();
        E[] constants = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= constants.length) {
            throw new UasProtocolException(
                "enum ordinal " + ordinal + " out of range for " + type.getSimpleName()
                    + " (" + constants.length + " constants)");
        }
        return constants[ordinal];
    }

    /** Writes a list using a per-element writer and a VarInt count. */
    public <T> UasCodec writeList(java.util.List<T> values, ItemWriter<T> writer, int maxElements) {
        if (values.size() > maxElements) {
            throw new UasProtocolException(
                "list of " + values.size() + " elements exceeds the " + maxElements + " element limit");
        }
        writeVarInt(values.size());
        for (T value : values) {
            writer.write(this, value);
        }
        return this;
    }

    /** Reads a list written by {@link #writeList}, bounded before allocation. */
    public <T> java.util.List<T> readList(ItemReader<T> reader, int maxElements) {
        int count = readVarInt();
        if (count < 0) {
            throw new UasProtocolException("negative list length: " + count);
        }
        if (count > maxElements) {
            throw UasProtocolException.oversized("list", count, maxElements);
        }
        java.util.List<T> values = new java.util.ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            values.add(reader.read(this));
        }
        return values;
    }

    /** Rejects any protocol version other than the supported one. */
    public UasCodec requireVersion(int expected) {
        int actual = readVarInt();
        if (actual != expected) {
            throw new UasProtocolException("unsupported protocol version " + actual + ", expected " + expected);
        }
        return this;
    }

    public interface ItemWriter<T> {
        void write(UasCodec codec, T value);
    }

    public interface ItemReader<T> {
        T read(UasCodec codec);
    }

    // ------------------------------------------------------------------ output

    private void writeRaw(byte[] value) {
        ensureWritable(value.length);
        System.arraycopy(value, 0, buffer, writeIndex, value.length);
        writeIndex += value.length;
    }

    /** Copies the written bytes into a new, exactly-sized array. */
    public byte[] toByteArray() {
        return java.util.Arrays.copyOf(buffer, writeIndex);
    }

    /** The backing array without copying. Only the first {@code writerIndex()} bytes are meaningful. */
    public byte[] backingArray() {
        return buffer;
    }

    /** Streams the written bytes to an output without materialising a copy. */
    public void writeTo(java.io.OutputStream out, int offset, int length) throws java.io.IOException {
        if (offset < 0 || length < 0 || offset + length > writeIndex) {
            throw new UasProtocolException(
                "invalid writeTo window offset=" + offset + " length=" + length + " size=" + writeIndex);
        }
        out.write(buffer, offset, length);
    }

    public UasCodec writeTo(java.io.OutputStream out) throws java.io.IOException {
        writeTo(out, 0, writeIndex);
        return this;
    }

    @Override
    public String toString() {
        return "UasCodec[read=" + readIndex + ", written=" + writeIndex + ", capacity=" + buffer.length + "]";
    }
}
