package dev.uas.api.block;

import dev.uas.api.UasValidationException;

/**
 * A UV rectangle in block-model units, {@code (u1, v1)} top-left to {@code (u2, v2)} bottom-right.
 *
 * <p>Minecraft's model space places {@code v=0} at the top of the texture; Bedrock's
 * geometry format places {@code uv_origin} at the top-left too, but flips the
 * handedness of the vertical axis per face. That conversion lives in the Bedrock
 * geometry compiler rather than here, so this type stays a faithful Java-side value.
 */
public record Uv(double u1, double v1, double u2, double v2) {

    public Uv {
        requireFinite(u1, "u1");
        requireFinite(v1, "v1");
        requireFinite(u2, "u2");
        requireFinite(v2, "v2");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new UasValidationException("uv component " + name + " is not finite: " + value);
        }
    }

    public static Uv of(double u1, double v1, double u2, double v2) {
        return new Uv(u1, v1, u2, v2);
    }

    /** The full-texture UV, used when a face does not declare an explicit rectangle. */
    public static Uv full() {
        return new Uv(0, 0, 16, 16);
    }

    /** Rebuilds a UV from the four-element array used by colour floats in JSON models. */
    public static Uv fromArray(double[] values) {
        if (values == null || values.length != 4) {
            throw new UasValidationException("uv must have exactly 4 components, got "
                + (values == null ? "null" : values.length));
        }
        return new Uv(values[0], values[1], values[2], values[3]);
    }

    public double width() {
        return u2 - u1;
    }

    public double height() {
        return v2 - v1;
    }

    public Uv translated(double du, double dv) {
        return new Uv(u1 + du, v1 + dv, u2 + du, v2 + dv);
    }

    public Uv scaled(double factor) {
        return new Uv(u1 * factor, v1 * factor, u2 * factor, v2 * factor);
    }

    /** True when the rectangle is non-degenerate and inside the model's 0..16 space. */
    public boolean isPlausible() {
        return width() > 0 && height() > 0 && u1 >= 0 && v1 >= 0 && u2 <= 16.5 && v2 <= 16.5;
    }
}
