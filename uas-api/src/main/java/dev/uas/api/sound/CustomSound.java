package dev.uas.api.sound;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A declarative custom sound event.
 *
 * <p>This is the neutral form of an entry in Java's {@code assets/<ns>/sounds.json}.
 * The Bedrock converter turns it into {@code sound_definitions.json} entries plus a
 * Java-key to Bedrock-event mapping, because Bedrock has no equivalent of the
 * "sound event with weighted variants" concept and must flatten it.
 */
public record CustomSound(
    NamespacedKey key,
    List<SoundVariant> variants,
    String subtitle,
    SoundCategory category,
    boolean replaceExisting,
    AttenuationCurve attenuation,
    double pitch,
    double volume) {

    public CustomSound {
        Objects.requireNonNull(key, "key");
        if (variants == null || variants.isEmpty()) {
            throw new UasValidationException("sound '" + key + "' must declare at least one variant");
        }
        variants = List.copyOf(variants);
        category = category == null ? SoundCategory.MASTER : category;
        attenuation = attenuation == null ? AttenuationCurve.vanilla() : attenuation;
        if (!Double.isFinite(pitch) || pitch <= 0) {
            throw new UasValidationException("sound pitch must be > 0, got " + pitch);
        }
        if (!Double.isFinite(volume) || volume < 0) {
            throw new UasValidationException("sound volume must be >= 0, got " + volume);
        }
    }

    public static Builder builder(NamespacedKey key) {
        return new Builder(key);
    }

    /** A single-file sound with Minecraft's default envelope. */
    public static CustomSound single(NamespacedKey key, NamespacedKey file) {
        return builder(key).variant(SoundVariant.of(file)).build();
    }

    public int totalWeight() {
        int total = 0;
        for (SoundVariant variant : variants) {
            total += variant.weight();
        }
        return total;
    }

    /** The variant that would be selected for a normalised random sample in {@code [0, 1)}. */
    public SoundVariant selectVariant(double sample) {
        if (sample < 0 || sample >= 1) {
            throw new IllegalArgumentException("sample must be in [0, 1), got " + sample);
        }
        int total = totalWeight();
        double cursor = sample * total;
        double running = 0;
        for (SoundVariant variant : variants) {
            running += variant.weight();
            if (cursor < running) {
                return variant;
            }
        }
        return variants.get(variants.size() - 1);
    }

    public boolean hasStreamedVariant() {
        return variants.stream().anyMatch(SoundVariant::stream);
    }

    /** The effective attenuation for a variant, falling back to the event's curve. */
    public AttenuationCurve attenuationFor(SoundVariant variant) {
        return variant.attenuation() == null ? attenuation : variant.attenuation();
    }

    public Builder toBuilder() {
        return new Builder(key)
            .variants(variants)
            .subtitle(subtitle)
            .category(category)
            .replaceExisting(replaceExisting)
            .attenuation(attenuation)
            .pitch(pitch)
            .volume(volume);
    }

    public static final class Builder {
        private final NamespacedKey key;
        private final List<SoundVariant> variants = new ArrayList<>();
        private String subtitle = "";
        private SoundCategory category = SoundCategory.MASTER;
        private boolean replaceExisting;
        private AttenuationCurve attenuation = AttenuationCurve.vanilla();
        private double pitch = 1.0;
        private double volume = 1.0;

        private Builder(NamespacedKey key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        public Builder variant(SoundVariant variant) {
            this.variants.add(Objects.requireNonNull(variant, "variant"));
            return this;
        }

        public Builder variants(List<SoundVariant> list) {
            this.variants.clear();
            this.variants.addAll(list);
            return this;
        }

        /** Convenience for the common single-file case. */
        public Builder file(NamespacedKey file) {
            return variant(SoundVariant.of(file));
        }

        public Builder subtitle(String value) {
            this.subtitle = value == null ? "" : value;
            return this;
        }

        public Builder category(SoundCategory value) {
            this.category = value;
            return this;
        }

        public Builder replaceExisting(boolean value) {
            this.replaceExisting = value;
            return this;
        }

        public Builder attenuation(AttenuationCurve curve) {
            this.attenuation = curve;
            return this;
        }

        public Builder pitch(double value) {
            this.pitch = value;
            return this;
        }

        public Builder volume(double value) {
            this.volume = value;
            return this;
        }

        public CustomSound build() {
            return new CustomSound(
                key, variants, subtitle, category, replaceExisting, attenuation, pitch, volume);
        }
    }
}
