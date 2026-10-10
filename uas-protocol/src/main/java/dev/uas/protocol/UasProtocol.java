package dev.uas.protocol;

/**
 * Wire-protocol constants shared by the Paper plugin-message bridge, the framed
 * Netty codec and the Geyser translation layer.
 */
public final class UasProtocol {

    /** Bump on any incompatible change to a packet layout or the frame header. */
    public static final int VERSION = 1;

    /** The plugin-messaging channel both sides register. */
    public static final String CHANNEL = "uas:channel";

    /** Frame magic: ASCII "UA". */
    public static final int MAGIC_0 = 0x55;

    public static final int MAGIC_1 = 0x41;

    /**
     * Hard ceiling on a single control frame. Control frames carry ids, positions
     * and small state maps -- never assets -- so this is generous and keeps a
     * hostile peer from turning the pipeline into an allocation amplifier.
     */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    /** Frames at or above this size are deflated on the wire. */
    public static final int COMPRESSION_THRESHOLD = 256;

    /** Ceiling on inflated frame size; the decompression-bomb guard. */
    public static final int MAX_DECOMPRESSED_BYTES = 8 << 20;

    /** Cap on state-map entries in a single UI packet. */
    public static final int MAX_STATE_ENTRIES = 256;

    /** Cap on list elements in any single packet. */
    public static final int MAX_LIST_ELEMENTS = 1024;

    /** Flag bit 0 of the frame header: the payload body is zlib-deflated. */
    public static final int FLAG_COMPRESSED = 0x01;

    /** Bits used by frame headers; all others are reserved and must be rejected. */
    public static final int FLAG_MASK = FLAG_COMPRESSED;

    private UasProtocol() {
    }
}
