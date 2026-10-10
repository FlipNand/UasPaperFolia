package dev.uas.protocol;

import java.util.Objects;

/**
 * A protocol version plus one packet: the unit that actually crosses the wire.
 *
 * <p>The version lives in the envelope rather than in each packet so a peer can
 * reject a whole stream on the first frame instead of failing halfway through
 * decoding a body it cannot understand.
 */
public record UasFrame(int protocolVersion, UasPacket packet) {

    public UasFrame {
        if (protocolVersion <= 0) {
            throw new UasProtocolException("frame protocol version must be > 0, got " + protocolVersion);
        }
        Objects.requireNonNull(packet, "packet");
    }

    public static UasFrame of(UasPacket packet) {
        return new UasFrame(UasProtocol.VERSION, packet);
    }

    public UasFrame requireVersion(int expected) {
        if (protocolVersion != expected) {
            throw new UasProtocolException(
                "frame speaks protocol " + protocolVersion + ", expected " + expected);
        }
        return this;
    }

    @Override
    public String toString() {
        return "UasFrame[v" + protocolVersion + " " + packet + "]";
    }
}
