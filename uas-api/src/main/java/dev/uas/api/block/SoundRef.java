package dev.uas.api.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import dev.uas.api.sound.AttenuationCurve;
import dev.uas.api.sound.SoundCategory;
import java.util.Objects;

/**
 * A concrete, playable reference to a sound event.
 *
 * <p>Separate from {@code CustomSound}: that declares the event and its file set,
 * while this is a "play this key here, at this volume/pitch" instruction which is
 * exactly what {@code PacketPlayCustomSound} serialises.
 */
public record SoundRef(
    NamespacedKey key,
    float volume,
    float pitch,
    SoundCategory category,
    AttenuationCurve attenuation,
    double attenuationDistance) {

    public SoundRef {
        Objects.requireNonNull(key, "key");
        category = category == null ? SoundCategory.MASTER : category;
        attenuation = attenuation == null ? AttenuationCurve.vanilla() : attenuation;
        if (!Float.isFinite(volume) || volume < 0) {
            throw new UasValidationException("sound volume must be >= 0, got " + volume);
        }
        if (!Float.isFinite(pitch) || pitch <= 0) {
            throw new UasValidationException("sound pitch must be > 0, got " + pitch);
        }
        if (!Double.isFinite(attenuationDistance) || attenuationDistance < 0) {
            throw new UasValidationException(
                "attenuation distance must be >= 0, got " + attenuationDistance);
        }
    }

    public static SoundRef of(NamespacedKey key) {
        return new SoundRef(key, 1f, 1f, SoundCategory.MASTER, AttenuationCurve.vanilla(), 16);
    }

    public static SoundRef of(NamespacedKey key, float volume, float pitch) {
        return new SoundRef(key, volume, pitch, SoundCategory.BLOCKS, AttenuationCurve.vanilla(), 16);
    }

    public static SoundRef blocks(NamespacedKey key, float volume, float pitch) {
        return new SoundRef(key, volume, pitch, SoundCategory.BLOCKS, AttenuationCurve.vanilla(), 16);
    }

    public SoundRef withCategory(SoundCategory newCategory) {
        return new SoundRef(key, volume, pitch, newCategory, attenuation, attenuationDistance);
    }

    public SoundRef withPitch(float newPitch) {
        return new SoundRef(key, volume, newPitch, category, attenuation, attenuationDistance);
    }

    /** Volume after attenuation for a listener at {@code distance}. */
    public float effectiveVolumeAt(double distance) {
        return (float) (volume * attenuation.gainAt(distance));
    }
}
