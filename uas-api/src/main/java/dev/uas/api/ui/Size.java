package dev.uas.api.ui;

import dev.uas.api.UasValidationException;

/**
 * A length that is resolved against a parent extent at layout time, so a single
 * declaration works on every client resolution.
 *
 * <p>Bedrock's custom UI JSON only understands pixels and percentages; the
 * {@code AUTO}/{@code FILL}/{@code ASPECT} units are resolved by the Java layout
 * pass and then emitted to Bedrock as concrete pixels.
 */
public record Size(Unit unit, double value) {

    public enum Unit {
        /** Device-independent pixels. */
        PIXELS,
        /** Percentage of the parent extent along the same axis. */
        PERCENT,
        /** Dictated by the element's own content. */
        AUTO,
        /** Stretch to the parent extent, minus any margins. */
        FILL,
        /** Keeps the element's aspect ratio: {@link #value} is the aspect multiplier. */
        ASPECT
    }

    public Size {
        if (unit == null) {
            throw new UasValidationException("size unit must not be null");
        }
        if (!Double.isFinite(value)) {
            throw new UasValidationException("size value is not finite: " + value);
        }
        switch (unit) {
            case PERCENT -> {
                if (value < 0 || value > 100) {
                    throw new UasValidationException("percentage size must be 0..100, got " + value);
                }
            }
            case ASPECT -> {
                if (value <= 0) {
                    throw new UasValidationException("aspect multiplier must be > 0, got " + value);
                }
            }
            case PIXELS -> {
                if (value < 0) {
                    throw new UasValidationException("pixel size must be >= 0, got " + value);
                }
            }
            case AUTO, FILL -> {
                // value is ignored
            }
        }
    }

    public static Size px(double value) {
        return new Size(Unit.PIXELS, value);
    }

    public static Size percent(double value) {
        return new Size(Unit.PERCENT, value);
    }

    public static Size auto() {
        return new Size(Unit.AUTO, 0);
    }

    public static Size fill() {
        return new Size(Unit.FILL, 0);
    }

    public static Size aspect(double multiplier) {
        return new Size(Unit.ASPECT, multiplier);
    }

    /**
     * Resolves this size against a parent extent.
     *
     * @param parentExtent the parent's extent along the same axis in pixels
     * @param intrinsic the element's own preferred extent, used by {@link Unit#AUTO}
     * @return the concrete pixel extent
     */
    public double resolve(double parentExtent, double intrinsic) {
        if (parentExtent < 0) {
            throw new IllegalArgumentException("parent extent must be >= 0, got " + parentExtent);
        }
        return switch (unit) {
            case PIXELS -> value;
            case PERCENT -> parentExtent * (value / 100.0);
            case AUTO -> intrinsic;
            case FILL -> parentExtent;
            case ASPECT -> intrinsic * value;
        };
    }

    public boolean isResolved() {
        return unit == Unit.PIXELS;
    }

    public boolean dependsOnContent() {
        return unit == Unit.AUTO || unit == Unit.ASPECT;
    }

    @Override
    public String toString() {
        return switch (unit) {
            case PIXELS -> value + "px";
            case PERCENT -> value + "%";
            case AUTO -> "auto";
            case FILL -> "fill";
            case ASPECT -> "aspect(" + value + ")";
        };
    }
}
