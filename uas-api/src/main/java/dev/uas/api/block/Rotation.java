package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import dev.uas.api.math.Vec3;
import java.util.Objects;

/**
 * A model element rotation about a pivot point.
 *
 * <p>Angles are restricted to the multiples of 22.5 degrees that Minecraft's model
 * format permits, because both the Java renderer and Bedrock's geometry parser
 * quantise to the same lattice.
 */
public record Rotation(double x, double y, double z, Vec3 origin, boolean rescale) {

    private static final double LATTICE = 22.5;
    private static final double EPSILON = 1e-6;

    public Rotation {
        Objects.requireNonNull(origin, "origin");
        requireLattice(x, "x");
        requireLattice(y, "y");
        requireLattice(z, "z");
        if (!origin.within(-16, 32)) {
            throw new UasValidationException("rotation origin must lie within -16..32, got " + origin);
        }
    }

    private static void requireLattice(double angle, String axis) {
        if (!Double.isFinite(angle)) {
            throw new UasValidationException("rotation " + axis + " is not finite: " + angle);
        }
        double steps = angle / LATTICE;
        if (Math.abs(steps - Math.rint(steps)) > EPSILON) {
            throw new UasValidationException(
                "rotation " + axis + " must be a multiple of " + LATTICE + " degrees, got " + angle);
        }
    }

    public static Rotation none() {
        return new Rotation(0, 0, 0, Vec3.ZERO, false);
    }

    public static Rotation of(double x, double y, double z) {
        return new Rotation(x, y, z, new Vec3(8, 8, 8), false);
    }

    public static Rotation around(double x, double y, double z, Vec3 origin) {
        return new Rotation(x, y, z, origin, false);
    }

    public Rotation withRescale(boolean newRescale) {
        return new Rotation(x, y, z, origin, newRescale);
    }

    public Rotation withOrigin(Vec3 newOrigin) {
        return new Rotation(x, y, z, newOrigin, rescale);
    }

    public boolean isIdentity() {
        return Math.abs(x) < EPSILON && Math.abs(y) < EPSILON && Math.abs(z) < EPSILON;
    }

    /** The largest absolute angle, used to size the geometry AABB expansion. */
    public double maxAngle() {
        return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
    }
}
