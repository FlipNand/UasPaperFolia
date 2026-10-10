package dev.uas.api.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.sound.AttenuationCurve;
import java.util.List;
import java.util.Objects;

/**
 * The five sound events a block plays: break, step, place, hit, fall.
 *
 * <p>Java exposes this as a {@code SoundGroup}; Bedrock expresses the same set as
 * {@code sound_definitions.json} entries referenced from the block definition, so
 * every slot here becomes one Bedrock sound event.
 */
public record BlockSoundGroup(
    NamespacedKey breakSound,
    NamespacedKey stepSound,
    NamespacedKey placeSound,
    NamespacedKey hitSound,
    NamespacedKey fallSound,
    float volume,
    float pitch,
    AttenuationCurve attenuation) {

    public BlockSoundGroup {
        Objects.requireNonNull(breakSound, "breakSound");
        Objects.requireNonNull(stepSound, "stepSound");
        Objects.requireNonNull(placeSound, "placeSound");
        Objects.requireNonNull(hitSound, "hitSound");
        Objects.requireNonNull(fallSound, "fallSound");
        attenuation = attenuation == null ? AttenuationCurve.vanilla() : attenuation;
        if (volume < 0) {
            throw new dev.uas.api.UasValidationException("sound group volume must be >= 0, got " + volume);
        }
        if (pitch <= 0) {
            throw new dev.uas.api.UasValidationException("sound group pitch must be > 0, got " + pitch);
        }
    }

    /** Declares the same event for all five slots. */
    public static BlockSoundGroup uniform(NamespacedKey sound) {
        return new BlockSoundGroup(sound, sound, sound, sound, sound, 1f, 1f, AttenuationCurve.vanilla());
    }

    public static BlockSoundGroup of(
        NamespacedKey step,
        NamespacedKey breakSound,
        NamespacedKey place,
        NamespacedKey hit,
        NamespacedKey fall) {
        return new BlockSoundGroup(breakSound, step, place, hit, fall, 1f, 1f, AttenuationCurve.vanilla());
    }

    public BlockSoundGroup withVolume(float newVolume) {
        return new BlockSoundGroup(breakSound, stepSound, placeSound, hitSound, fallSound,
            newVolume, pitch, attenuation);
    }

    public BlockSoundGroup withPitch(float newPitch) {
        return new BlockSoundGroup(breakSound, stepSound, placeSound, hitSound, fallSound,
            volume, newPitch, attenuation);
    }

    public BlockSoundGroup withAttenuation(AttenuationCurve curve) {
        return new BlockSoundGroup(breakSound, stepSound, placeSound, hitSound, fallSound,
            volume, pitch, curve);
    }

    public SoundRef breakRef() {
        return new SoundRef(breakSound, volume, pitch, dev.uas.api.sound.SoundCategory.BLOCKS, attenuation, 16);
    }

    public SoundRef stepRef() {
        return new SoundRef(stepSound, volume, pitch, dev.uas.api.sound.SoundCategory.BLOCKS, attenuation, 16);
    }

    public SoundRef placeRef() {
        return new SoundRef(placeSound, volume, pitch, dev.uas.api.sound.SoundCategory.BLOCKS, attenuation, 16);
    }

    public SoundRef hitRef() {
        return new SoundRef(hitSound, volume, pitch, dev.uas.api.sound.SoundCategory.BLOCKS, attenuation, 16);
    }

    public SoundRef fallRef() {
        return new SoundRef(fallSound, volume, pitch, dev.uas.api.sound.SoundCategory.BLOCKS, attenuation, 16);
    }

    /** All five event keys, in declaration order. */
    public List<NamespacedKey> eventKeys() {
        return List.of(breakSound, stepSound, placeSound, hitSound, fallSound);
    }

    /** The Java {@code SoundGroup} name is derived from the break sound's path. */
    public String suggestedGroupName() {
        return breakSound.namespace() + "." + breakSound.path().replace('/', '.');
    }
}
