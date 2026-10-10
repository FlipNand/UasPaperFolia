package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import dev.uas.api.math.Vec3;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One cuboid element of a block model, in Minecraft block-model units (0..16 per axis).
 *
 * <p>Instances are immutable. The {@link Builder} performs the same range and
 * volume checks the Java model loader applies, so an invalid model is rejected at
 * declaration time.
 */
public record Cuboid(
    Vec3 from,
    Vec3 to,
    Rotation rotation,
    Map<Face, FaceTexture> faces,
    boolean shade,
    Integer lightEmission) {

    /** Minecraft allows models to extend past the unit cube; Bedrock tolerates the same range. */
    public static final double MIN_COORD = -16;
    public static final double MAX_COORD = 32;

    public Cuboid {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(faces, "faces");
        rotation = rotation == null ? Rotation.none() : rotation;

        if (!from.within(MIN_COORD, MAX_COORD) || !to.within(MIN_COORD, MAX_COORD)) {
            throw new UasValidationException(
                "cuboid coordinates must lie within " + MIN_COORD + ".." + MAX_COORD
                    + ", got " + from + " -> " + to);
        }
        if (from.x() >= to.x() || from.y() >= to.y() || from.z() >= to.z()) {
            throw new UasValidationException(
                "cuboid must have positive volume, got " + from + " -> " + to);
        }
        if (faces.isEmpty()) {
            throw new UasValidationException("cuboid must declare at least one face");
        }
        if (lightEmission != null && (lightEmission < 0 || lightEmission > 15)) {
            throw new UasValidationException("cuboid lightEmission out of range 0..15: " + lightEmission);
        }
        // Wrapped rather than copied through Map.copyOf. An EnumMap iterates in Face declaration
        // order, which is the order a model should list its faces in, and the immutable map
        // factories instead spread entries by a per-JVM salt. That salt made the emitted model
        // differ between two identical servers, and the pack archive's hash is its identity, so
        // caches and the push protocol never agreed the pack was the same one.
        faces = Collections.unmodifiableMap(new EnumMap<>(faces));
    }

    public static Builder builder(Vec3 from, Vec3 to) {
        return new Builder(from, to);
    }

    public static Builder builder(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        return new Builder(new Vec3(fromX, fromY, fromZ), new Vec3(toX, toY, toZ));
    }

    /** The axis-aligned bounds the cuboid occupies, ignoring rotation. */
    public BoundingBox bounds() {
        return BoundingBox.of(from, to);
    }

    /** The bounds after applying the element rotation, conservative (pivot-expanded). */
    public BoundingBox rotatedBounds() {
        if (rotation.isIdentity()) {
            return bounds();
        }
        Vec3[] corners = {
            from, to,
            new Vec3(from.x(), from.y(), to.z()),
            new Vec3(from.x(), to.y(), from.z()),
            new Vec3(from.x(), to.y(), to.z()),
            new Vec3(to.x(), from.y(), from.z()),
            new Vec3(to.x(), from.y(), to.z()),
            new Vec3(to.x(), to.y(), from.z()),
        };
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Vec3 corner : corners) {
            Vec3 rotated = rotate(corner);
            minX = Math.min(minX, rotated.x());
            minY = Math.min(minY, rotated.y());
            minZ = Math.min(minZ, rotated.z());
            maxX = Math.max(maxX, rotated.x());
            maxY = Math.max(maxY, rotated.y());
            maxZ = Math.max(maxZ, rotated.z());
        }
        return BoundingBox.of(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    /**
     * Rotates a point about the element pivot using Minecraft's order:
     * Z, then Y, then X (the order the vanilla model loader applies).
     */
    public Vec3 rotate(Vec3 point) {
        if (rotation.isIdentity()) {
            return point;
        }
        Vec3 origin = rotation.origin();
        double radX = Math.toRadians(rotation.x());
        double radY = Math.toRadians(rotation.y());
        double radZ = Math.toRadians(rotation.z());

        double x = point.x() - origin.x();
        double y = point.y() - origin.y();
        double z = point.z() - origin.z();

        // Z
        double cosZ = Math.cos(radZ);
        double sinZ = Math.sin(radZ);
        double x1 = x * cosZ - y * sinZ;
        double y1 = x * sinZ + y * cosZ;
        double z1 = z;

        // Y
        double cosY = Math.cos(radY);
        double sinY = Math.sin(radY);
        double x2 = x1 * cosY + z1 * sinY;
        double y2 = y1;
        double z2 = -x1 * sinY + z1 * cosY;

        // X
        double cosX = Math.cos(radX);
        double sinX = Math.sin(radX);
        double x3 = x2;
        double y3 = y2 * cosX - z2 * sinX;
        double z3 = y2 * sinX + z2 * cosX;

        return new Vec3(x3 + origin.x(), y3 + origin.y(), z3 + origin.z());
    }

    public Map<String, String> textureSlots() {
        Map<String, String> slots = new LinkedHashMap<>();
        for (FaceTexture face : faces.values()) {
            if (face.isSlotReference()) {
                slots.put(face.slotName(), face.slotName());
            }
        }
        return slots;
    }

    public static final class Builder {
        private final Vec3 from;
        private final Vec3 to;
        private Rotation rotation = Rotation.none();
        private final Map<Face, FaceTexture> faces = new EnumMap<>(Face.class);
        private boolean shade = true;
        private Integer lightEmission;

        private Builder(Vec3 from, Vec3 to) {
            this.from = Objects.requireNonNull(from, "from");
            this.to = Objects.requireNonNull(to, "to");
        }

        public Builder rotation(Rotation newRotation) {
            this.rotation = newRotation;
            return this;
        }

        public Builder face(Face face, FaceTexture texture) {
            this.faces.put(Objects.requireNonNull(face, "face"), Objects.requireNonNull(texture, "texture"));
            return this;
        }

        /** Applies the same texture reference to every face. */
        public Builder allFaces(String texture) {
            for (Face face : Face.values()) {
                faces.put(face, FaceTexture.of(texture));
            }
            return this;
        }

        public Builder shade(boolean newShade) {
            this.shade = newShade;
            return this;
        }

        public Builder lightEmission(Integer newLightEmission) {
            this.lightEmission = newLightEmission;
            return this;
        }

        public Cuboid build() {
            return new Cuboid(from, to, rotation, faces, shade, lightEmission);
        }
    }
}
