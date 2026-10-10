package dev.uas.protocol.packet;

import dev.uas.protocol.UasCodec;
import dev.uas.protocol.UasPacket;
import dev.uas.protocol.UasProtocol;

/**
 * Capability negotiation, sent in both directions on channel join.
 *
 * <p>The server sends its capability mask and the client answers with its own
 * platform and mask. Everything downstream keys off the intersection, which is how
 * a Bedrock client gets a modal form where a Java client gets a HUD overlay without
 * the pack author writing two declarations.
 */
public final class UasHelloPacket implements UasPacket {

    public static final int ID = 0;

    public enum Platform {
        JAVA,
        BEDROCK,
        PROXY
    }

    /** Capability bits; unknown bits are ignored so a newer peer degrades cleanly. */
    public static final long CAP_ASSET_STREAMING = 1L;
    public static final long CAP_CUSTOM_BLOCKS = 1L << 1;
    public static final long CAP_CUSTOM_SOUNDS = 1L << 2;
    public static final long CAP_CUSTOM_UI_OVERLAY = 1L << 3;
    public static final long CAP_CUSTOM_UI_FORM = 1L << 4;
    public static final long CAP_RANGE_REQUESTS = 1L << 5;
    public static final long CAP_BLOCK_DISPLAY_BACKEND = 1L << 6;
    public static final long CAP_NOTE_BLOCK_BACKEND = 1L << 7;

    private int protocolVersion;
    private Platform platform = Platform.JAVA;
    private long capabilities;
    private String clientVersion = "";

    public UasHelloPacket() {
    }

    public UasHelloPacket(int protocolVersion, Platform platform, long capabilities, String clientVersion) {
        this.protocolVersion = protocolVersion;
        this.platform = platform == null ? Platform.JAVA : platform;
        this.capabilities = capabilities;
        this.clientVersion = clientVersion == null ? "" : clientVersion;
    }

    public static UasHelloPacket server(long capabilities, String version) {
        return new UasHelloPacket(UasProtocol.VERSION, Platform.PROXY, capabilities, version);
    }

    public static UasHelloPacket javaClient(long capabilities, String version) {
        return new UasHelloPacket(UasProtocol.VERSION, Platform.JAVA, capabilities, version);
    }

    public static UasHelloPacket bedrockClient(long capabilities, String version) {
        return new UasHelloPacket(UasProtocol.VERSION, Platform.BEDROCK, capabilities, version);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public Platform platform() {
        return platform;
    }

    public long capabilities() {
        return capabilities;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public boolean supports(long capability) {
        return (capabilities & capability) != 0;
    }

    public UasHelloPacket withCapability(long capability) {
        this.capabilities |= capability;
        return this;
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public void write(UasCodec codec) {
        codec.writeVarInt(protocolVersion);
        codec.writeEnum(platform);
        codec.writeVarLong(capabilities);
        codec.writeString(clientVersion);
    }

    @Override
    public void read(UasCodec codec) {
        this.protocolVersion = codec.readVarInt();
        if (protocolVersion != UasProtocol.VERSION) {
            throw new dev.uas.protocol.UasProtocolException(
                "peer speaks UAS protocol " + protocolVersion + ", this build speaks " + UasProtocol.VERSION);
        }
        this.platform = codec.readEnum(Platform.class);
        this.capabilities = codec.readVarLong();
        this.clientVersion = codec.readString();
    }

    @Override
    public String toString() {
        return "UasHelloPacket[v" + protocolVersion + ", " + platform + ", caps=0x"
            + Long.toHexString(capabilities) + ", client='" + clientVersion + "']";
    }
}
