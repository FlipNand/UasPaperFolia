package dev.uas.protocol;

/**
 * A typed, bidirectional control message.
 *
 * <p>Implementations are mutable plain objects with a no-argument constructor so
 * the registry can instantiate them before reading, which keeps decoding
 * allocation-simple and avoids reflection at decode time.
 *
 * <p>{@link #read} must validate everything it reads: it runs on bytes that may
 * come from a client.
 */
public interface UasPacket {

    /** Stable wire id. Never reuse an id for a different packet. */
    int id();

    /** Serialises this packet's body. The frame header is written by the frame codec. */
    void write(UasCodec codec);

    /** Populates this packet from a body previously produced by {@link #write}. */
    void read(UasCodec codec);

    default String name() {
        return getClass().getSimpleName();
    }
}
