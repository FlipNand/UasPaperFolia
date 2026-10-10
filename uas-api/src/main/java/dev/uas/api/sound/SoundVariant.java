package dev.uas.api.sound;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.Objects;

/**
 * One concrete audio file backing a sound event.
 *
 * <p>A sound event usually has several variants; the client picks one per play
 * according to {@link #weight()} and randomises pitch inside {@link #pitchRange()}.
 *
 * @param key the {@code namespace:path} of the audio file, without the
 *     {@code .ogg} extension or the leading {@code sounds/} directory
 * @param weight relative selection weight; higher means played more often
 * @param stream whether the client streams the file instead of pre-loading it
 * @param attenuation per-variant override, or {@code null} to inherit the event's curve
 * @param pitchMin lower bound of the random pitch multiplier
 * @param pitchMax upper bound of the random pitch multiplier
 * @param volume base volume multiplier
 */
public record SoundVariant(
    NamespacedKey key,
    int weight,
    boolean stream,
    AttenuationCurve attenuation,
    double pitchMin,
    double pitchMax,
    double volume) {

    public SoundVariant {
        Objects.requireNonNull(key, "key");
        if (weight <= 0) {
            throw new UasValidationException("sound variant weight must be > 0, got " + weight);
        }
        if (!Double.isFinite(pitchMin) || !Double.isFinite(pitchMax) || pitchMin <= 0 || pitchMax < pitchMin) {
            throw new UasValidationException(
                "invalid pitch range " + pitchMin + ".." + pitchMax + " for " + key);
        }
        if (!Double.isFinite(volume) || volume < 0) {
            throw new UasValidationException("sound variant volume must be >= 0, got " + volume);
        }
    }

    public static SoundVariant of(NamespacedKey key) {
        return new SoundVariant(key, 1, false, null, 0.8, 1.2, 1.0);
    }

    public static SoundVariant of(NamespacedKey key, int weight) {
        return new SoundVariant(key, weight, false, null, 0.8, 1.2, 1.0);
    }

    /** A streamed variant, for long audio such as music or ambience. */
    public static SoundVariant streamed(NamespacedKey key) {
        return new SoundVariant(key, 1, true, null, 1.0, 1.0, 1.0);
    }

    /** The path the client requests, relative to the pack root. */
    public String assetPath() {
        return key.namespace() + "/sounds/" + key.path() + ".ogg";
    }

    /** Bedrock event key form, used when one Bedrock sound event maps to many Java variants. */
    public String bedrockEventName(String eventNamespace) {
        return eventNamespace + "." + key.path().replace('/', '.');
    }

    public SoundVariant withAttenuation(AttenuationCurve curve) {
        return new SoundVariant(key, weight, stream, curve, pitchMin, pitchMax, volume);
    }

    public SoundVariant withWeight(int newWeight) {
        return new SoundVariant(key, newWeight, stream, attenuation, pitchMin, pitchMax, volume);
    }

    public SoundVariant withPitchRange(double min, double max) {
        return new SoundVariant(key, weight, stream, attenuation, min, max, volume);
    }

    public SoundVariant withVolume(double newVolume) {
        return new SoundVariant(key, weight, stream, attenuation, pitchMin, pitchMax, newVolume);
    }
}
