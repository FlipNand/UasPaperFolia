package dev.uas.netty.protocol;

import dev.uas.protocol.PacketRegistry;
import dev.uas.protocol.UasFrame;
import dev.uas.protocol.UasFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Serialises UAS frames onto the wire.
 *
 * <p>Delegates to {@link UasFrameCodec} rather than re-implementing the layout, so
 * the pipeline codec and the array-based codec used by the Geyser bridge can never
 * drift apart.
 */
public final class UasFrameEncoder extends MessageToByteEncoder<UasFrame> {

    private final PacketRegistry registry;
    private final int compressionThreshold;

    public UasFrameEncoder(PacketRegistry registry) {
        this(registry, dev.uas.protocol.UasProtocol.COMPRESSION_THRESHOLD);
    }

    public UasFrameEncoder(PacketRegistry registry, int compressionThreshold) {
        super(UasFrame.class);
        this.registry = registry;
        this.compressionThreshold = compressionThreshold;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, UasFrame msg, ByteBuf out) {
        byte[] encoded = UasFrameCodec.encode(msg, registry, compressionThreshold);
        out.writeBytes(encoded);
    }
}
