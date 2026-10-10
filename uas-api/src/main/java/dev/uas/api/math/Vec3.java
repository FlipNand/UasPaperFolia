package dev.uas.api.math;

import dev.uas.api.UasValidationException;

/**
 * Immutable 3-component vector.
 *
 * <p>Block model space uses Minecraft's convention: a full block spans
 * {@code 0..16} on every axis, with +Y up.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);

    public Vec3 {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    private static void requireFinite(double value, String axis) {
        if (!Double.isFinite(value)) {
            throw new UasValidationException("vector component " + axis + " is not finite: " + value);
        }
    }

    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 scale(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public Vec3 withX(double newX) {
        return new Vec3(newX, y, z);
    }

    public Vec3 withY(double newY) {
        return new Vec3(x, newY, z);
    }

    public Vec3 withZ(double newZ) {
        return new Vec3(x, y, newZ);
    }

    /** Converts block-model units (0..16) into block space (0..1). */
    public Vec3 toBlockSpace() {
        return scale(1.0 / 16.0);
    }

    public static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        return new Vec3(
            from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.z + (to.z - from.z) * t);
    }

    public boolean isZero() {
        return x == 0 && y == 0 && z == 0;
    }

    /** True when every component lies within {@code [min, max]}. */
    public boolean within(double min, double max) {
        return x >= min && x <= max && y >= min && y <= max && z >= min && z <= max;
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x);
    }

    public Vec3 normalized() {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            return ZERO;
        }
        return scale(1.0 / length);
    }

    @Override
    public String toString() {
        return "(" + trim(x) + ", " + trim(y) + ", " + trim(z) + ")";
    }

    static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
