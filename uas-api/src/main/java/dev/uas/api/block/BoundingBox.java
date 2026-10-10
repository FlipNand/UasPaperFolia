package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import dev.uas.api.math.Vec3;
import java.util.Objects;

/**
 * An axis-aligned box in block space (0..1 per axis for a normal block).
 *
 * <p>Used for both collision and selection boxes. Collision boxes are the ones
 * Bedrock's custom-block {@code collision_box} component consumes.
 */
public record BoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public BoundingBox {
        requireOrdered(minX, maxX, "x");
        requireOrdered(minY, maxY, "y");
        requireOrdered(minZ, maxZ, "z");
    }

    private static void requireOrdered(double min, double max, String axis) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new UasValidationException("bounding box " + axis + " bounds are not finite");
        }
        if (min > max) {
            throw new UasValidationException(
                "bounding box " + axis + " min (" + min + ") exceeds max (" + max + ")");
        }
    }

    public static BoundingBox full() {
        return new BoundingBox(0, 0, 0, 1, 1, 1);
    }

    public static BoundingBox empty() {
        return new BoundingBox(0, 0, 0, 0, 0, 0);
    }

    /** Convenience constructor taking vectors, in block-model units (0..16). */
    public static BoundingBox of(Vec3 from, Vec3 to) {
        return new BoundingBox(from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
    }

    public static BoundingBox of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Same box expressed in block units (divided by 16). */
    public BoundingBox toBlockSpace() {
        return new BoundingBox(minX / 16, minY / 16, minZ / 16, maxX / 16, maxY / 16, maxZ / 16);
    }

    public BoundingBox clampToUnitCube() {
        return new BoundingBox(
            Math.max(0, minX), Math.max(0, minY), Math.max(0, minZ),
            Math.min(1, maxX), Math.min(1, maxY), Math.min(1, maxZ));
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double depth() {
        return maxZ - minZ;
    }

    public double volume() {
        return width() * height() * depth();
    }

    public boolean isEmpty() {
        return volume() <= 0;
    }

    public boolean encloses(BoundingBox other) {
        return minX <= other.minX && minY <= other.minY && minZ <= other.minZ
            && maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ;
    }

    public BoundingBox union(BoundingBox other) {
        return new BoundingBox(
            Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public boolean intersects(BoundingBox other) {
        return minX < other.maxX && maxX > other.minX
            && minY < other.maxY && maxY > other.minY
            && minZ < other.maxZ && maxZ > other.minZ;
    }

    /** The full unit cube, optionally shrunk on each axis to emulate slabs/steps. */
    public static BoundingBox unit(double shrinkX, double shrinkY, double shrinkZ) {
        return new BoundingBox(shrinkX, shrinkY, shrinkZ, 1 - shrinkX, 1 - shrinkY, 1 - shrinkZ);
    }

    public static BoundingBox require(BoundingBox box, String subject) {
        Objects.requireNonNull(box, subject);
        return box;
    }
}
