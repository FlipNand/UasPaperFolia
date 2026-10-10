package dev.uas.api.block;

import dev.uas.api.UasValidationException;
import java.util.Objects;

/**
 * A single textured face of a cuboid.
 *
 * @param texture the resolved texture reference. Either a {@code #slot} token that
 *     resolves against the model's texture map, or a {@code namespace:path} key.
 * @param uv the UV rectangle, or {@code null} to use the whole face
 * @param rotation clockwise texture rotation in degrees: 0, 90, 180 or 270
 * @param tintIndex biome tint index, or {@code null} for no tinting
 * @param cullFace the neighbour face this face is culled against, or {@code null}
 */
public record FaceTexture(String texture, Uv uv, int rotation, Integer tintIndex, Face cullFace) {

    public FaceTexture {
        Objects.requireNonNull(texture, "texture");
        if (texture.isBlank()) {
            throw new UasValidationException("face texture reference must not be blank");
        }
        if (rotation % 90 != 0) {
            throw new UasValidationException("face texture rotation must be a multiple of 90, got " + rotation);
        }
        rotation = Math.floorMod(rotation, 360);
        if (tintIndex != null && (tintIndex < -1 || tintIndex > 15)) {
            throw new UasValidationException("tintIndex out of range: " + tintIndex);
        }
    }

    public static FaceTexture of(String texture) {
        return new FaceTexture(texture, null, 0, null, null);
    }

    public static FaceTexture of(String texture, Uv uv) {
        return new FaceTexture(texture, uv, 0, null, null);
    }

    public static FaceTexture of(String texture, Uv uv, int rotation) {
        return new FaceTexture(texture, uv, rotation, null, null);
    }

    /** The UV actually used when rendering, substituting the whole face when unset. */
    public Uv effectiveUv() {
        return uv == null ? Uv.full() : uv;
    }

    public FaceTexture withTexture(String newTexture) {
        return new FaceTexture(newTexture, uv, rotation, tintIndex, cullFace);
    }

    public FaceTexture withUv(Uv newUv) {
        return new FaceTexture(texture, newUv, rotation, tintIndex, cullFace);
    }

    public FaceTexture withCullFace(Face newCullFace) {
        return new FaceTexture(texture, uv, rotation, tintIndex, newCullFace);
    }

    /** True when the reference is a texture-map slot such as {@code #all}. */
    public boolean isSlotReference() {
        return texture.startsWith("#");
    }

    public String slotName() {
        if (!isSlotReference()) {
            throw new IllegalStateException("not a slot reference: " + texture);
        }
        return texture.substring(1);
    }
}
