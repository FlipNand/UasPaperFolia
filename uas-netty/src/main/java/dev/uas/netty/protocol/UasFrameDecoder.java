package dev.uas.netty.protocol;

import dev.uas.protocol.PacketRegistry;
import dev.uas.protocol.UasCodec;
import dev.uas.protocol.UasFrame;
import dev.uas.protocol.UasFrameCodec;
import dev.uas.protocol.UasProtocol;
import dev.uas.protocol.UasProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Decodes UAS control frames out of the byte stream.
 *
 * <p>Frames are length-prefixed, so the decoder can wait for a whole frame before
 * touching the body. It refuses to copy anything before the declared length has
 * actually arrived, and it caps that length at
 * {@link UasProtocol#MAX_FRAME_BYTES} -- a peer cannot make the decoder reserve
 * memory it has not sent.
 *
 * <p>The public entry points for tests and for the Geyser bridge operate on an
 * array, so the same framing is exercised without a pipeline.
 */
public final class UasFrameDecoder extends ByteToMessageDecoder {

    /** Head bytes are magic (2) + version VarInt (up to 5) + flags (1) + length VarInt (up to 5). */
    private static final int MAX_HEADER_BYTES = 13;

    private final PacketRegistry registry;

    public UasFrameDecoder(PacketRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= 2) {
            if (!looksLikeMagic(in)) {
                throw new UasProtocolException("stream does not start with the UAS frame magic");
            }
            int start = in.readerIndex();
            int headerEnd = peekHeaderEnd(in);
            if (headerEnd < 0) {
                // Header not fully arrived yet; the declared length is what tells us
                // whether the body is complete, so there is nothing else to do.
                return;
            }
            int payloadLength = peekPayloadLength(in);
            if (payloadLength < 0 || payloadLength > UasProtocol.MAX_FRAME_BYTES) {
                throw new UasProtocolException(
                    "declared frame payload length " + payloadLength + " is out of range");
            }
            int total = headerEnd - start + payloadLength;
            if (in.readableBytes() < total) {
                // Wait for the rest instead of buffering a partial frame ourselves.
                return;
            }
            byte[] frame = new byte[total];
            in.getBytes(start, frame);
            in.skipBytes(total);
            out.add(UasFrameCodec.decode(frame, registry));
        }
    }

    private static boolean looksLikeMagic(ByteBuf in) {
        int base = in.readerIndex();
        // Compared as ints: the magic constants are in 0..255, so the byte widens cleanly.
        return in.getByte(base) == UasProtocol.MAGIC_0
            && in.getByte(base + 1) == UasProtocol.MAGIC_1;
    }

    /** Index just past the header, or {@code -1} when the header is still incomplete. */
    private static int peekHeaderEnd(ByteBuf in) {
        int base = in.readerIndex();
        int limit = Math.min(in.writerIndex(), base + MAX_HEADER_BYTES);
        int index = base + 2;
        // protocol version VarInt
        index = skipVarInt(in, index, limit);
        if (index < 0) {
            return -1;
        }
        // flags byte
        if (index >= limit) {
            return -1;
        }
        index++;
        // payload length VarInt
        index = skipVarInt(in, index, limit);
        return index < 0 ? -1 : index;
    }

    /** The declared payload length, or {@code -1} when it has not arrived yet. */
    private static int peekPayloadLength(ByteBuf in) {
        int base = in.readerIndex();
        int limit = Math.min(in.writerIndex(), base + MAX_HEADER_BYTES);
        int index = base + 2;
        index = skipVarInt(in, index, limit);
        if (index < 0 || index >= limit) {
            return -1;
        }
        index++;
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (index + i >= limit) {
                return -1;
            }
            byte current = in.getByte(index + i);
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    /** Advances past a VarInt, or returns {@code -1} if it is incomplete. */
    private static int skipVarInt(ByteBuf in, int index, int limit) {
        for (int i = 0; i < 5; i++) {
            if (index + i >= limit) {
                return -1;
            }
            if ((in.getByte(index + i) & 0x80) == 0) {
                return index + i + 1;
            }
        }
        return -1;
    }

    /** Convenience for non-Netty callers: decode one frame from a byte array. */
    public static UasFrame decodeArray(byte[] data, PacketRegistry registry) {
        return UasFrameCodec.decode(UasCodec.decoder(data), registry);
    }
}
