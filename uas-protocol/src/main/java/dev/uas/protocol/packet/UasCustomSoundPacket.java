package dev.uas.protocol.packet;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.SoundRef;
import dev.uas.api.sound.SoundCategory;
import dev.uas.protocol.UasCodec;
import dev.uas.protocol.UasPacket;
import java.util.UUID;

/**
 * Plays a custom sound for a listener, optionally positioned in the world.
 *
 * <p>Java clients resolve the key through the resource pack they already streamed
 * from us. Bedrock has no client-side "play this resource-pack key" primitive, so
 * the Geyser bridge substitutes the nearest Bedrock sound event -- which is why the
 * position and category travel even when the key itself cannot be forwarded.
 */
public final class UasCustomSoundPacket implements UasPacket {

    public static final int ID = 1;

    /** Flag bit 0: the emitter has a world position. Cleared means "play locally". */
    private static final int FLAG_POSITIONAL = 1;

    /** Flag bit 1: an emitter uuid is present. */
    private static final int FLAG_HAS_EMITTER = 1 << 1;

    private NamespacedKey soundKey = NamespacedKey.uas("missing");
    private double x;
    private double y;
    private double z;
    private float volume = 1f;
    private float pitch = 1f;
    private SoundCategory category = SoundCategory.MASTER;
    private boolean positional;
    private boolean hasEmitter;
    private UUID emitter;

    public UasCustomSoundPacket() {
    }

    public UasCustomSoundPacket(SoundRef ref, double x, double y, double z, UUID emitter) {
        this.soundKey = ref.key();
        this.volume = ref.volume();
        this.pitch = ref.pitch();
        this.category = ref.category();
        this.x = x;
        this.y = y;
        this.z = z;
        this.positional = true;
        this.emitter = emitter;
        this.hasEmitter = emitter != null;
    }

    /** A non-positional cue, played at full volume regardless of listener position. */
    public static UasCustomSoundPacket local(SoundRef ref) {
        UasCustomSoundPacket packet = new UasCustomSoundPacket();
        packet.soundKey = ref.key();
        packet.volume = ref.volume();
        packet.pitch = ref.pitch();
        packet.category = ref.category();
        return packet;
    }

    public NamespacedKey soundKey() {
        return soundKey;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    public SoundCategory category() {
        return category;
    }

    public boolean isPositional() {
        return positional;
    }

    public UUID emitter() {
        return emitter;
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public void write(UasCodec codec) {
        codec.writeNamespacedKey(soundKey);
        int flags = 0;
        if (positional) {
            flags |= FLAG_POSITIONAL;
        }
        if (hasEmitter) {
            flags |= FLAG_HAS_EMITTER;
        }
        codec.writeByte(flags);
        codec.writeFloat(volume);
        codec.writeFloat(pitch);
        codec.writeEnum(category);
        if (positional) {
            codec.writeDouble(x);
            codec.writeDouble(y);
            codec.writeDouble(z);
        }
        if (hasEmitter) {
            codec.writeUuid(emitter);
        }
    }

    @Override
    public void read(UasCodec codec) {
        this.soundKey = codec.readNamespacedKey();
        int flags = codec.readByte() & 0xFF;
        if ((flags & ~(FLAG_POSITIONAL | FLAG_HAS_EMITTER)) != 0) {
            throw new dev.uas.protocol.UasProtocolException(
                "UasCustomSoundPacket has unknown flag bits set: 0x" + Integer.toHexString(flags));
        }
        this.positional = (flags & FLAG_POSITIONAL) != 0;
        this.hasEmitter = (flags & FLAG_HAS_EMITTER) != 0;
        this.volume = codec.readFloat();
        this.pitch = codec.readFloat();
        this.category = codec.readEnum(SoundCategory.class);
        if (!Float.isFinite(volume) || volume < 0 || volume > 64) {
            throw new dev.uas.protocol.UasProtocolException("sound volume out of range: " + volume);
        }
        if (!Float.isFinite(pitch) || pitch <= 0 || pitch > 64) {
            throw new dev.uas.protocol.UasProtocolException("sound pitch out of range: " + pitch);
        }
        if (positional) {
            this.x = codec.readDouble();
            this.y = codec.readDouble();
            this.z = codec.readDouble();
        }
        if (hasEmitter) {
            this.emitter = codec.readUuid();
        }
    }

    @Override
    public String toString() {
        return "UasCustomSoundPacket[" + soundKey + (positional ? " at (" + x + ", " + y + ", " + z + ")" : " local")
            + ", vol=" + volume + ", pitch=" + pitch + ", " + category + "]";
    }
}
