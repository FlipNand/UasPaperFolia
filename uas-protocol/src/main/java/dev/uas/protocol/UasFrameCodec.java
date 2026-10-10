package dev.uas.protocol;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes and decodes {@link UasFrame}s, with optional zlib compression.
 *
 * <p>Two properties matter more than raw speed here, because these frames come
 * from and go to clients:
 *
 * <ul>
 *   <li><strong>Bounded decompression.</strong> The inflate loop refuses to produce
 *       more than {@link UasProtocol#MAX_DECOMPRESSED_BYTES}, so a small hostile
 *       frame cannot expand into an out-of-memory condition.</li>
 *   <li><strong>Streaming inflate.</strong> The payload is inflated in chunks rather
 *       than by declaring an output array sized from the frame, so a lying length
 *       prefix cannot force a large allocation either.</li>
 * </ul>
 */
public final class UasFrameCodec {

    private UasFrameCodec() {
    }

    /** Encodes with the default compression threshold. */
    public static byte[] encode(UasFrame frame, PacketRegistry registry) {
        return encode(frame, registry, UasProtocol.COMPRESSION_THRESHOLD);
    }

    /**
     * Encodes a frame.
     *
     * @param compressionThreshold payloads at or above this many bytes are deflated;
     *     pass {@code Integer.MAX_VALUE} to disable compression entirely
     */
    public static byte[] encode(UasFrame frame, PacketRegistry registry, int compressionThreshold) {
        if (frame == null) {
            throw new UasProtocolException("frame must not be null");
        }
        if (registry.type(frame.packet().id()).isEmpty()) {
            throw new UasProtocolException(
                "refusing to encode unregistered packet id " + frame.packet().id());
        }

        UasCodec inner = UasCodec.encoder(128);
        inner.writeVarInt(frame.packet().id());
        frame.packet().write(inner);
        byte[] body = inner.toByteArray();

        int flags = 0;
        byte[] payload = body;
        if (body.length >= compressionThreshold && body.length > 0) {
            byte[] deflated = deflate(body);
            // Only keep the compressed form when it actually helps; a payload that
            // expands would otherwise cost the client CPU for nothing.
            if (deflated.length < body.length) {
                payload = deflated;
                flags |= UasProtocol.FLAG_COMPRESSED;
            }
        }

        UasCodec out = UasCodec.encoder(payload.length + 16);
        out.writeByte(UasProtocol.MAGIC_0);
        out.writeByte(UasProtocol.MAGIC_1);
        out.writeVarInt(frame.protocolVersion());
        out.writeByte(flags);
        out.writeVarInt(payload.length);
        out.writeRawBytes(payload);

        byte[] encoded = out.toByteArray();
        if (encoded.length > UasProtocol.MAX_FRAME_BYTES) {
            throw UasProtocolException.oversized("frame", encoded.length, UasProtocol.MAX_FRAME_BYTES);
        }
        return encoded;
    }

    /** Decodes a complete frame. Trailing bytes after the payload are a protocol violation. */
    public static UasFrame decode(byte[] data, PacketRegistry registry) {
        return decode(UasCodec.decoder(data), registry);
    }

    public static UasFrame decode(UasCodec codec, PacketRegistry registry) {
        if (codec == null) {
            throw new UasProtocolException("codec must not be null");
        }
        int magic0 = codec.readByte() & 0xFF;
        int magic1 = codec.readByte() & 0xFF;
        if (magic0 != UasProtocol.MAGIC_0 || magic1 != UasProtocol.MAGIC_1) {
            throw new UasProtocolException(String.format(
                "bad frame magic 0x%02X%02X (expected 0x%02X%02X)",
                magic0, magic1, UasProtocol.MAGIC_0, UasProtocol.MAGIC_1));
        }
        int version = codec.readVarInt();
        int flags = codec.readByte() & 0xFF;
        if ((flags & ~UasProtocol.FLAG_MASK) != 0) {
            throw new UasProtocolException(
                "frame sets reserved flag bits: 0x" + Integer.toHexString(flags & ~UasProtocol.FLAG_MASK));
        }
        int payloadLength = codec.readVarInt();
        if (payloadLength < 0) {
            throw new UasProtocolException("negative payload length: " + payloadLength);
        }
        if (payloadLength > UasProtocol.MAX_FRAME_BYTES) {
            throw UasProtocolException.oversized("frame payload", payloadLength, UasProtocol.MAX_FRAME_BYTES);
        }
        byte[] payload = codec.readRawBytes(payloadLength);
        if ((flags & UasProtocol.FLAG_COMPRESSED) != 0) {
            payload = inflate(payload, UasProtocol.MAX_DECOMPRESSED_BYTES);
        }

        UasCodec body = UasCodec.decoder(payload);
        int packetId = body.readVarInt();
        UasPacket packet = registry.create(packetId);
        packet.read(body);
        if (body.readableBytes() != 0) {
            throw new UasProtocolException(
                body.readableBytes() + " trailing bytes after " + packet.name() + " body");
        }
        return new UasFrame(version, packet);
    }

    /** True when the leading bytes are a UAS frame magic, without consuming anything. */
    public static boolean looksLikeFrame(byte[] data, int offset) {
        return data != null
            && offset + 1 < data.length
            && (data[offset] & 0xFF) == UasProtocol.MAGIC_0
            && (data[offset + 1] & 0xFF) == UasProtocol.MAGIC_1;
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.max(32, data.length / 2));
            byte[] chunk = new byte[4096];
            while (!deflater.finished()) {
                int produced = deflater.deflate(chunk);
                if (produced == 0 && deflater.needsInput()) {
                    break;
                }
                sink.write(chunk, 0, produced);
            }
            return sink.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Inflates with a hard output ceiling.
     *
     * @throws UasProtocolException if the stream would exceed {@code maxBytes}
     */
    static byte[] inflate(byte[] data, int maxBytes) {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.min(1024, Math.max(16, data.length * 2)));
            byte[] chunk = new byte[4096];
            while (!inflater.finished()) {
                int produced;
                try {
                    produced = inflater.inflate(chunk);
                } catch (DataFormatException e) {
                    throw new UasProtocolException("malformed compressed frame payload", e);
                }
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new UasProtocolException("truncated compressed frame payload");
                    }
                    break;
                }
                if (sink.size() + produced > maxBytes) {
                    throw UasProtocolException.oversized(
                        "decompressed frame", (long) sink.size() + produced, maxBytes);
                }
                sink.write(chunk, 0, produced);
            }
            return sink.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
