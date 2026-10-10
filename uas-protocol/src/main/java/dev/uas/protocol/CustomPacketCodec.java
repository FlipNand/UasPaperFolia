package dev.uas.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * The one place a plugin turns a packet into bytes and back.
 *
 * <p>{@link UasCodec} is the byte-level writer, {@link UasFrameCodec} is the envelope, and
 * {@link PacketRegistry} resolves ids to types. Every caller needs all three in the same order
 * and with the same envelope version, and a caller that assembles them slightly wrong produces
 * bytes that fail on a client with no server-side symptom -- the worst possible failure mode.
 * So the composition lives here, once, and the platform bridges call it rather than the three
 * underlying pieces.
 *
 * <p>Framing carries the protocol version and an optional compression flag, which is what lets
 * a Bedrock translation and a Java companion agree on one wire format even though one of them
 * never speaks the Minecraft protocol.
 *
 * <h2>Decoding is defensive by construction</h2>
 *
 * <p>Inbound bytes arrive from a client, so they are untrusted. The order is fixed:
 * {@link #looksLikePacket} checks the cheap leading marker and length <em>before</em>
 * {@link #decode} is allowed to allocate or index anything, and a malformed frame is reported
 * as an empty result rather than an exception, because a hostile client must not be able to
 * raise an exception inside the server's plugin-message dispatch.
 */
public final class CustomPacketCodec {

    /** The plugin-message channel both client families use. */
    public static final String CHANNEL = UasProtocol.CHANNEL;

    private final PacketRegistry registry;

    public CustomPacketCodec() {
        this(PacketRegistry.standard());
    }

    public CustomPacketCodec(PacketRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** A codec over the standard packet set, which is what every platform bridge wants. */
    public static CustomPacketCodec standard() {
        return new CustomPacketCodec(PacketRegistry.standard());
    }

    /** Encodes a packet into a framed byte array ready to send on {@link #CHANNEL}. */
    public byte[] encode(UasPacket packet) {
        Objects.requireNonNull(packet, "packet");
        return UasFrameCodec.encode(UasFrame.of(packet), registry);
    }

    /** Encodes with an explicit compression threshold; a negative threshold disables it. */
    public byte[] encode(UasPacket packet, int compressionThreshold) {
        Objects.requireNonNull(packet, "packet");
        return UasFrameCodec.encode(UasFrame.of(packet), registry, compressionThreshold);
    }

    /**
     * Decodes a frame.
     *
     * @throws UasProtocolException if the bytes are a frame but malformed; callers that cannot
     *     tolerate that -- anything on a client-controlled path -- should use
     *     {@link #tryDecode(byte[])} instead
     */
    public UasFrame decode(byte[] data) {
        return UasFrameCodec.decode(data, registry);
    }

    /**
     * Decodes untrusted bytes, reporting failure as an empty result.
     *
     * <p>This is the entry point the bridges use. Nothing a client sends should be able to
     * raise an exception through the engine, so the validation and the decode are one operation
     * here rather than a caller that must remember to check first.
     */
    public Optional<UasFrame> tryDecode(byte[] data) {
        if (!looksLikePacket(data)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(data));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** Decodes and narrows to a packet type, useful for a bridge with one interest. */
    public <T extends UasPacket> Optional<T> tryDecodeAs(byte[] data, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return tryDecode(data).map(UasFrame::packet).filter(type::isInstance).map(type::cast);
    }

    /**
     * True when the bytes could be one of our frames.
     *
     * <p>Cheap and non-throwing on purpose: it is called on every inbound plugin message, most
     * of which belong to other plugins on the same channel namespace, to reject them before any
     * allocation happens.
     */
    public static boolean looksLikePacket(byte[] data) {
        if (data == null || data.length < 3 || data.length > UasProtocol.MAX_FRAME_BYTES) {
            return false;
        }
        return UasFrameCodec.looksLikeFrame(data, 0);
    }

    /** The framed size a packet will occupy, without encoding it. */
    public int framedSize(UasPacket packet) {
        return encode(packet).length;
    }

    /** The packet types this codec can resolve, for diagnostics. */
    public java.util.Set<Integer> knownPacketIds() {
        return registry.knownIds();
    }

    public int protocolVersion() {
        return UasProtocol.VERSION;
    }

    @Override
    public String toString() {
        return "CustomPacketCodec[channel=" + CHANNEL + ", protocol=" + protocolVersion()
            + ", packets=" + registry.size() + "]";
    }
}
