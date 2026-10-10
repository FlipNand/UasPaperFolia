package dev.uas.protocol.packet;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.Face;
import dev.uas.protocol.UasCodec;
import dev.uas.protocol.UasPacket;
import java.util.UUID;

/**
 * A custom block interaction, in either direction.
 *
 * <p>Client to server it reports what the player did; server to client it drives
 * synchronised feedback such as a state-matrix block flipping its property, or a
 * display entity starting a transform animation. {@code stateHash} carries the
 * server's view of the block state so a desynchronised client can be corrected
 * instead of the server trusting whatever the client claims.
 */
public final class UasBlockInteractionPacket implements UasPacket {

    public static final int ID = 2;

    public enum Action {
        BREAK_START,
        BREAK_COMPLETE,
        PLACE,
        INTERACT,
        STEP_ON,
        STEP_OFF,
        HIT,
        STATE_SYNC
    }

    /** Bit 0: a block key is present. Bit 1: a player uuid is present. */
    private static final int FLAG_HAS_BLOCK_KEY = 1;
    private static final int FLAG_HAS_PLAYER = 1 << 1;

    private int x;
    private int y;
    private int z;
    private Action action = Action.INTERACT;
    private Face face = Face.UP;
    private UUID playerId;
    private NamespacedKey blockKey;
    private int stateHash;
    private double fineX;
    private double fineY;
    private double fineZ;

    public UasBlockInteractionPacket() {
    }

    public UasBlockInteractionPacket(
        int x, int y, int z, Action action, Face face, UUID playerId, NamespacedKey blockKey, int stateHash) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.face = face == null ? Face.UP : face;
        this.playerId = playerId;
        this.blockKey = blockKey;
        this.stateHash = stateHash;
    }

    /** Builds a state-sync packet sent from the server. */
    public static UasBlockInteractionPacket stateSync(
        int x, int y, int z, NamespacedKey blockKey, int stateHash) {
        return new UasBlockInteractionPacket(x, y, z, Action.STATE_SYNC, Face.UP, null, blockKey, stateHash);
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Action action() {
        return action;
    }

    public Face face() {
        return face;
    }

    public UUID playerId() {
        return playerId;
    }

    public NamespacedKey blockKey() {
        return blockKey;
    }

    public int stateHash() {
        return stateHash;
    }

    /** The exact interaction point within the block, in 0..1 block-local space. */
    public double fineX() {
        return fineX;
    }

    public double fineY() {
        return fineY;
    }

    public double fineZ() {
        return fineZ;
    }

    public UasBlockInteractionPacket withFinePosition(double newX, double newY, double newZ) {
        if (newX < 0 || newX > 1 || newY < 0 || newY > 1 || newZ < 0 || newZ > 1) {
            throw new IllegalArgumentException("fine position components must be in 0..1");
        }
        this.fineX = newX;
        this.fineY = newY;
        this.fineZ = newZ;
        return this;
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public void write(UasCodec codec) {
        codec.writeInt(x);
        codec.writeInt(y);
        codec.writeInt(z);
        codec.writeEnum(action);
        codec.writeEnum(face);
        codec.writeInt(stateHash);
        int flags = 0;
        if (blockKey != null) {
            flags |= FLAG_HAS_BLOCK_KEY;
        }
        if (playerId != null) {
            flags |= FLAG_HAS_PLAYER;
        }
        codec.writeByte(flags);
        if (blockKey != null) {
            codec.writeNamespacedKey(blockKey);
        }
        if (playerId != null) {
            codec.writeUuid(playerId);
        }
        codec.writeFloat((float) fineX);
        codec.writeFloat((float) fineY);
        codec.writeFloat((float) fineZ);
    }

    @Override
    public void read(UasCodec codec) {
        this.x = codec.readInt();
        this.y = codec.readInt();
        this.z = codec.readInt();
        this.action = codec.readEnum(Action.class);
        this.face = codec.readEnum(Face.class);
        this.stateHash = codec.readInt();
        int flags = codec.readByte() & 0xFF;
        if ((flags & ~(FLAG_HAS_BLOCK_KEY | FLAG_HAS_PLAYER)) != 0) {
            throw new dev.uas.protocol.UasProtocolException(
                "UasBlockInteractionPacket has unknown flag bits set: 0x" + Integer.toHexString(flags));
        }
        if ((flags & FLAG_HAS_BLOCK_KEY) != 0) {
            this.blockKey = codec.readNamespacedKey();
        }
        if ((flags & FLAG_HAS_PLAYER) != 0) {
            this.playerId = codec.readUuid();
        }
        this.fineX = codec.readFloat();
        this.fineY = codec.readFloat();
        this.fineZ = codec.readFloat();
        if (!Float.isFinite((float) fineX) || fineX < 0 || fineX > 1
            || fineY < 0 || fineY > 1 || fineZ < 0 || fineZ > 1) {
            throw new dev.uas.protocol.UasProtocolException(
                "fine position out of range: (" + fineX + ", " + fineY + ", " + fineZ + ")");
        }
    }

    @Override
    public String toString() {
        return "UasBlockInteractionPacket[" + action + " at (" + x + ", " + y + ", " + z + ")"
            + " face=" + face + (blockKey == null ? "" : " block=" + blockKey)
            + " stateHash=" + stateHash + "]";
    }
}
