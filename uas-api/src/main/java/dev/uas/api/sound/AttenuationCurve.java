package dev.uas.api.sound;

import dev.uas.api.UasValidationException;

/**
 * A distance-to-volume curve applied to a custom sound.
 *
 * <p>Minecraft's vanilla model is fixed: a linear rolloff out to 16 blocks.
 * Declaring the curve explicitly lets a pack reproduce that behaviour exactly
 * ({@link #vanilla()}) while also allowing longer-range cues such as a boss roar
 * or a jukebox, and gives the Bedrock bridge a concrete formula to evaluate
 * because Bedrock resource packs cannot express attenuation at all.
 */
public record AttenuationCurve(
    double minDistance,
    double maxDistance,
    double rolloffFactor,
    Easing easing,
    boolean audibleBeyondRange) {

    /** Minecraft's default: linear falloff, inaudible past 16 blocks. */
    public static final double VANILLA_MAX_DISTANCE = 16.0;

    public AttenuationCurve {
        if (!Double.isFinite(minDistance) || minDistance < 0) {
            throw new UasValidationException("minDistance must be finite and >= 0, got " + minDistance);
        }
        if (!Double.isFinite(maxDistance) || maxDistance <= 0) {
            throw new UasValidationException("maxDistance must be finite and > 0, got " + maxDistance);
        }
        if (maxDistance <= minDistance) {
            throw new UasValidationException(
                "maxDistance (" + maxDistance + ") must exceed minDistance (" + minDistance + ")");
        }
        if (!Double.isFinite(rolloffFactor) || rolloffFactor <= 0) {
            throw new UasValidationException("rolloffFactor must be finite and > 0, got " + rolloffFactor);
        }
        if (easing == null) {
            throw new UasValidationException("easing must not be null");
        }
    }

    public static AttenuationCurve vanilla() {
        return new AttenuationCurve(1.0, VANILLA_MAX_DISTANCE, 1.0, Easing.LINEAR, false);
    }

    public static AttenuationCurve inverseSquare(double maxDistance) {
        return new AttenuationCurve(1.0, maxDistance, 1.0, Easing.INVERSE_SQUARE, false);
    }

    public static AttenuationCurve of(double minDistance, double maxDistance, Easing easing) {
        return new AttenuationCurve(minDistance, maxDistance, 1.0, easing, false);
    }

    /**
     * The gain multiplier at a listener distance.
     *
     * <p>Inside {@code minDistance} the sound plays at full volume; past
     * {@code maxDistance} it is silent unless {@link #audibleBeyondRange} is set.
     */
    public double gainAt(double distance) {
        if (Double.isNaN(distance) || distance < 0) {
            throw new IllegalArgumentException("distance must be >= 0, got " + distance);
        }
        if (distance <= minDistance) {
            return 1.0;
        }
        if (distance >= maxDistance) {
            return audibleBeyondRange ? easing.apply(1.0) : 0.0;
        }
        double t = (distance - minDistance) / (maxDistance - minDistance);
        double eased = easing.apply(Math.min(1.0, t * rolloffFactor));
        double gain = 1.0 - eased;
        return Math.max(0.0, Math.min(1.0, gain));
    }

    /** True when a listener at this distance would hear nothing at all. */
    public boolean isSilentAt(double distance) {
        return gainAt(distance) <= 0.0;
    }

    /** The distance at which the gain first drops below the given threshold. */
    public double distanceAtGain(double gain) {
        if (gain <= 0 || gain >= 1) {
            throw new IllegalArgumentException("gain threshold must be inside (0, 1), got " + gain);
        }
        double low = minDistance;
        double high = maxDistance;
        for (int i = 0; i < 48; i++) {
            double mid = (low + high) / 2;
            if (gainAt(mid) > gain) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }
}
