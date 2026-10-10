package dev.uas.core.sound;

import dev.uas.api.NamespacedKey;
import dev.uas.api.sound.AttenuationCurve;
import dev.uas.api.sound.SoundCategory;
import dev.uas.api.sound.SoundVariant;
import java.util.Objects;

/**
 * A specific sound to play, after variant selection and volume interpolation.
 *
 * <p>A declared {@link dev.uas.api.sound.CustomSound} is a set of weighted variants
 * with pitch ranges; what the network layer and the server actually need is one
 * concrete file, one volume and one pitch. That reduction happens once, here, so the
 * Java path, the Bedrock path and the packet payload all agree on exactly the same
 * sound instead of each picking their own variant.
 */
public record ResolvedSound(
    NamespacedKey eventKey,
    NamespacedKey assetKey,
    SoundVariant variant,
    float volume,
    float pitch,
    SoundCategory category,
    AttenuationCurve attenuation) {

    public ResolvedSound {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(assetKey, "assetKey");
        Objects.requireNonNull(variant, "variant");
        category = category == null ? SoundCategory.MASTER : category;
        attenuation = attenuation == null ? AttenuationCurve.vanilla() : attenuation;
    }

    /** Volume after both the variant's and the group's multipliers are applied. */
    public float volumeAt(double distance) {
        double gain = Math.max(0d, attenuation.gainAt(distance));
        return (float) Math.max(0d, volume * gain);
    }

    public boolean isAudibleAt(double distance) {
        return volumeAt(distance) > 0f;
    }

    @Override
    public String toString() {
        return eventKey + " -> " + assetKey + " vol=" + volume + " pitch=" + pitch
            + " category=" + category + (variant.stream() ? " (streamed)" : "");
    }
}
