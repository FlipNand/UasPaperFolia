package dev.uas.api.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.UasValidationException;
import dev.uas.api.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A full block model: cuboid elements, a texture slot map, and render hints.
 *
 * <p>Deliberately shaped like a Java block model so that
 * {@code assets/<ns>/models/block/<name>.json} can be emitted verbatim, while
 * still carrying the extra metadata (animations, render layer) the Bedrock
 * geometry compiler needs.
 */
public record BlockGeometry(
    List<Cuboid> elements,
    Map<String, String> textures,
    RenderLayer renderLayer,
    boolean ambientOcclusion,
    String parent,
    Map<NamespacedKey, AnimatedTexture> animations) {

    private static final int MAX_TEXTURE_SLOT_DEPTH = 16;

    public BlockGeometry {
        if (elements == null || elements.isEmpty()) {
            throw new UasValidationException("block geometry must contain at least one element");
        }
        elements = List.copyOf(elements);
        // Declaration order is preserved rather than normalised with Map.copyOf. The immutable
        // map factories spread entries by a per-JVM salt, so their iteration order differs
        // between runs of the same code. Anything derived by iterating the slots -- a Java
        // model, a Bedrock block definition, or a texture map -- would then emit different
        // bytes for identical declarations, and the compiled pack archive would differ between
        // two identical servers. That is not cosmetic: the archive hash is the pack's identity,
        // so caches and the push protocol never agree the pack is the same one.
        textures = textures == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(textures));
        animations = animations == null ? Map.of() : Map.copyOf(animations);
        renderLayer = renderLayer == null ? RenderLayer.SOLID : renderLayer;
    }

    public static BlockGeometry of(List<Cuboid> elements, Map<String, String> textures, RenderLayer layer) {
        return new BlockGeometry(elements, textures, layer, true, null, Map.of());
    }

    /** A plain full cube using one texture, mainly useful for tests and defaults. */
    public static BlockGeometry unitCube(Map<String, String> textures, RenderLayer layer) {
        return new BlockGeometry(
            List.of(Cuboid.builder(0, 0, 0, 16, 16, 16).allFaces("#all").build()),
            textures,
            layer,
            true,
            null,
            Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The bounds of the geometry after element rotation, in block space (0..1).
     * Rotation expansion is conservative: it expands to the rotated corners rather
     * than shrinking to a tight fit, which is what the vanilla block-state AABB does.
     */
    public BoundingBox rotatedBounds() {
        BoundingBox union = null;
        for (Cuboid element : elements) {
            BoundingBox box = element.rotatedBounds().toBlockSpace();
            union = union == null ? box : union.union(box);
        }
        return union == null ? BoundingBox.full() : union;
    }

    /** The un-rotated bounds, in block space. */
    public BoundingBox staticBounds() {
        BoundingBox union = null;
        for (Cuboid element : elements) {
            BoundingBox box = element.bounds().toBlockSpace();
            union = union == null ? box : union.union(box);
        }
        return union == null ? BoundingBox.full() : union;
    }

    public boolean hasRotation() {
        return elements.stream().anyMatch(element -> !element.rotation().isIdentity());
    }

    /** True when the geometry is exactly the vanilla full cube. */
    public boolean isUnitCube() {
        if (elements.size() != 1 || hasRotation()) {
            return false;
        }
        BoundingBox box = elements.get(0).bounds().toBlockSpace();
        return box.equals(BoundingBox.full());
    }

    /** True when every cuboid is axis-aligned and none is rotated -- a Bedrock-safe default geometry. */
    public boolean isAxisAligned() {
        return !hasRotation();
    }

    /**
     * Resolves a face's texture reference to a concrete {@link NamespacedKey}.
     *
     * <p>Slot references ({@code #all}) chain through the texture map. A missing
     * slot is a hard error: silently emitting a broken model is worse than failing
     * the build, because the client shows a purple/black block with no diagnostics.
     */
    public NamespacedKey resolveTexture(FaceTexture face) {
        if (!face.isSlotReference()) {
            return NamespacedKey.parse(face.texture());
        }
        String slot = face.slotName();
        for (int depth = 0; depth < MAX_TEXTURE_SLOT_DEPTH; depth++) {
            String value = textures.get(slot);
            if (value == null) {
                throw new UasValidationException(
                    "texture slot '#'" + slot + "' is referenced but not declared in the texture map "
                        + textures.keySet());
            }
            if (!value.startsWith("#")) {
                return NamespacedKey.parse(value);
            }
            slot = value.substring(1);
        }
        throw new UasValidationException(
            "texture slot chain starting at '#" + face.slotName() + "' exceeds "
                + MAX_TEXTURE_SLOT_DEPTH + " levels (cycle?)");
    }

    /** Every concrete texture key this geometry references, in element/face order. */
    public List<NamespacedKey> referencedTextures() {
        List<NamespacedKey> resolved = new ArrayList<>();
        for (Cuboid element : elements) {
            for (Face face : Face.values()) {
                FaceTexture texture = element.faces().get(face);
                if (texture == null) {
                    continue;
                }
                NamespacedKey key = resolveTexture(texture);
                if (!resolved.contains(key)) {
                    resolved.add(key);
                }
            }
        }
        return resolved;
    }

    /** Textures that carry an animation descriptor. */
    public Map<NamespacedKey, AnimatedTexture> animatedTextures() {
        Map<NamespacedKey, AnimatedTexture> relevant = new LinkedHashMap<>();
        for (NamespacedKey key : referencedTextures()) {
            AnimatedTexture animation = animations.get(key);
            if (animation != null) {
                relevant.put(key, animation);
            }
        }
        return relevant;
    }

    public Builder toBuilder() {
        return new Builder()
            .elements(elements)
            .textures(textures)
            .renderLayer(renderLayer)
            .ambientOcclusion(ambientOcclusion)
            .parent(parent)
            .animations(animations);
    }

    public static final class Builder {
        private final List<Cuboid> elements = new ArrayList<>();
        private final Map<String, String> textures = new LinkedHashMap<>();
        private final Map<NamespacedKey, AnimatedTexture> animations = new LinkedHashMap<>();
        private RenderLayer renderLayer = RenderLayer.SOLID;
        private boolean ambientOcclusion = true;
        private String parent;

        public Builder element(Cuboid cuboid) {
            this.elements.add(Objects.requireNonNull(cuboid, "cuboid"));
            return this;
        }

        public Builder elements(List<Cuboid> cuboids) {
            this.elements.clear();
            this.elements.addAll(cuboids);
            return this;
        }

        /** Convenience: a single full cube textured with one key. */
        public Builder fullCube(NamespacedKey texture) {
            return texture("#all", texture.toString())
                .element(Cuboid.builder(0, 0, 0, 16, 16, 16).allFaces("#all").build());
        }

        /** Convenience: a single cuboid spanning the given block-model-unit box. */
        public Builder cuboid(
            Vec3 from,
            Vec3 to,
            NamespacedKey texture,
            RenderLayer layer) {
            this.renderLayer = layer;
            return texture("#all", texture.toString())
                .element(Cuboid.builder(from, to).allFaces("#all").build());
        }

        /**
         * Declares a texture slot.
         *
         * <p>The slot may be written with or without its {@code #}: the model authors write
         * {@code #all} when referencing it from a face, while resolution looks the slot up
         * without the marker. Normalising on the way in means both spellings name the same
         * slot -- otherwise a geometry built through this builder could not resolve its own
         * textures, which is exactly the failure {@link BlockGeometry#resolveTexture} exists
         * to make loud.
         */
        public Builder texture(String slot, String reference) {
            this.textures.put(normaliseSlot(Objects.requireNonNull(slot, "slot")),
                Objects.requireNonNull(reference, "reference"));
            return this;
        }

        public Builder textures(Map<String, String> map) {
            Objects.requireNonNull(map, "map");
            this.textures.clear();
            map.forEach((slot, reference) ->
                this.textures.put(normaliseSlot(slot), Objects.requireNonNull(reference, "reference")));
            return this;
        }

        private static String normaliseSlot(String slot) {
            return slot.startsWith("#") ? slot.substring(1) : slot;
        }

        public Builder animation(AnimatedTexture animation) {
            NamespacedKey key = Objects.requireNonNull(animation, "animation").texture();
            this.animations.put(key, animation);
            return this;
        }

        public Builder animations(Map<NamespacedKey, AnimatedTexture> map) {
            this.animations.clear();
            this.animations.putAll(map);
            return this;
        }

        public Builder renderLayer(RenderLayer layer) {
            this.renderLayer = layer;
            return this;
        }

        public Builder ambientOcclusion(boolean value) {
            this.ambientOcclusion = value;
            return this;
        }

        public Builder parent(String parentModel) {
            this.parent = parentModel;
            return this;
        }

        public BlockGeometry build() {
            return new BlockGeometry(elements, textures, renderLayer, ambientOcclusion, parent, animations);
        }
    }
}
