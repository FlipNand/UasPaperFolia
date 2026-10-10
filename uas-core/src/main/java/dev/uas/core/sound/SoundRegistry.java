package dev.uas.core.sound;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockSoundGroup;
import dev.uas.api.block.SoundRef;
import dev.uas.api.sound.AttenuationCurve;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.sound.SoundVariant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Registered custom sounds, and the reduction from a declared sound to a played one.
 *
 * <p>Java's {@code sounds.json} supports weighted variant selection: a footstep
 * declared with four files should not sound identical every time. Vanilla performs
 * that selection client-side, but a custom transport cannot -- it has to name one
 * file in the packet. So selection moves to the server, and the choice is made
 * <em>once per event</em> and reused for every recipient, so two players standing next
 * to each other hear the same footstep rather than two different ones.
 */
public final class SoundRegistry {

    private final Map<NamespacedKey, CustomSound> sounds = new ConcurrentHashMap<>();
    private final RandomGenerator random;

    public SoundRegistry() {
        this(RandomGenerator.getDefault());
    }

    /** Injectable randomness, so variant selection is testable rather than flaky. */
    public SoundRegistry(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public CustomSound register(CustomSound sound) {
        Objects.requireNonNull(sound, "sound");
        sounds.put(sound.key(), sound);
        return sound;
    }

    public Optional<CustomSound> sound(NamespacedKey key) {
        return Optional.ofNullable(sounds.get(key));
    }

    public Set<NamespacedKey> keys() {
        return Set.copyOf(sounds.keySet());
    }

    public int size() {
        return sounds.size();
    }

    public void clear() {
        sounds.clear();
    }

    /**
     * Resolves a declared sound to one concrete variant, volume and pitch.
     *
     * <p>Falls back to the reference's own declared volume when no custom sound is
     * registered under the key: the Java client may well have the file from the pack
     * even though the server never declared a variant list for it. Refusing to play in
     * that case would be a silent failure of a working sound.
     */
    public ResolvedSound resolve(SoundRef ref) {
        return resolve(ref, random.nextDouble());
    }

    /** Deterministic form, used by tests and by anything that needs reproducibility. */
    public ResolvedSound resolve(SoundRef ref, double variantSample) {
        Objects.requireNonNull(ref, "ref");
        CustomSound declared = sounds.get(ref.key());
        if (declared == null) {
            SoundVariant implicit = SoundVariant.of(ref.key(), 1);
            return new ResolvedSound(
                ref.key(), ref.key(), implicit, ref.volume(), ref.pitch(),
                ref.category(), ref.attenuation());
        }

        SoundVariant variant = declared.selectVariant(clampSample(variantSample));
        double pitch = ref.pitch() * pickPitch(variant, variantSample);
        double volume = ref.volume() * declared.volume() * variant.volume();
        AttenuationCurve attenuation = declared.attenuationFor(variant);

        return new ResolvedSound(
            ref.key(), variant.key(), variant,
            (float) Math.max(0d, volume), (float) Math.max(0.01d, pitch),
            ref.category(), attenuation);
    }

    /** Resolves a block's sound for one interaction, or empty when it declares none. */
    public Optional<ResolvedSound> resolveBlockSound(BlockSoundGroup group, BlockSoundEvent event) {
        SoundRef ref = event.refFrom(group);
        return ref == null ? Optional.empty() : Optional.of(resolve(ref));
    }

    /** The variant a sample selects; exposed for the Bedrock side that re-uses the choice. */
    public SoundVariant selectVariant(NamespacedKey key, double sample) {
        CustomSound declared = sounds.get(key);
        if (declared == null) {
            throw new IllegalArgumentException("no custom sound registered for '" + key + "'");
        }
        return declared.selectVariant(clampSample(sample));
    }

    private static double pickPitch(SoundVariant variant, double sample) {
        double min = variant.pitchMin();
        double max = variant.pitchMax();
        if (max <= min) {
            return min;
        }
        // A second, decorrelated sample so pitch does not track the variant choice.
        double second = (sample * 0.6180339887498949d) % 1.0d;
        return min + (max - min) * second;
    }

    private static double clampSample(double sample) {
        if (!Double.isFinite(sample)) {
            return 0d;
        }
        return Math.min(Math.max(sample, 0d), 0.999999999d);
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("sounds", Integer.toString(sounds.size()));
        int streamed = 0;
        int variants = 0;
        for (CustomSound sound : sounds.values()) {
            variants += sound.variants().size();
            if (sound.hasStreamedVariant()) {
                streamed++;
            }
        }
        report.put("variants", Integer.toString(variants));
        report.put("streamedSounds", Integer.toString(streamed));
        return report;
    }
}
