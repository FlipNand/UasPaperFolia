package dev.uas.api.sound;

/**
 * Normalised easing functions used by {@link AttenuationCurve}.
 *
 * <p>All functions map {@code t} in {@code [0, 1]} to a gain multiplier, clamping
 * out-of-range input so callers cannot produce a gain outside {@code [0, 1]}.
 */
public enum Easing {
    /** Constant gain across the whole range. */
    LINEAR {
        @Override
        public double apply(double t) {
            return clamp(t);
        }
    },
    /** Inverse-square falloff, matching Minecraft's vanilla sound attenuation. */
    INVERSE_SQUARE {
        @Override
        public double apply(double t) {
            double clamped = clamp(t);
            return 1.0 / (1.0 + 15.0 * clamped * clamped);
        }
    },
    /** Slow start, fast finish. */
    EASE_IN_QUAD {
        @Override
        public double apply(double t) {
            double clamped = clamp(t);
            return clamped * clamped;
        }
    },
    /** Fast start, slow finish. */
    EASE_OUT_QUAD {
        @Override
        public double apply(double t) {
            double clamped = clamp(t);
            return 1.0 - (1.0 - clamped) * (1.0 - clamped);
        }
    },
    /** A hard cut-off past the curve's midpoint; useful for radio-style effects. */
    STEP {
        @Override
        public double apply(double t) {
            return clamp(t) < 0.5 ? 1.0 : 0.0;
        }
    };

    public abstract double apply(double t);

    static double clamp(double t) {
        if (Double.isNaN(t)) {
            return 0;
        }
        return Math.max(0, Math.min(1, t));
    }
}
