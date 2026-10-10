package dev.uas.geyser.jit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.block.BlockGeometry;
import dev.uas.api.block.Cuboid;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.block.Face;
import dev.uas.api.block.FaceTexture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles a Java block model into a Bedrock {@code minecraft:geometry} document.
 *
 * <p>This is the heart of the "JIT Bedrock conversion" claim, and it is a pure function
 * on purpose: no Geyser types, no server, no I/O. Two consequences follow, and both are
 * the point. It can be unit-tested against exact expected JSON, which is the only way to
 * know a coordinate mapping is right without a Bedrock client in the loop; and it can be
 * reused by the pack generator, so the geometry a Bedrock client downloads is produced by
 * the same code that produced the geometry Geyser was told about -- not a second,
 * drifting implementation.
 *
 * <h2>The coordinate mapping, stated explicitly</h2>
 *
 * <p>Java block models and Bedrock block geometry disagree about where the block's origin
 * is, and getting this wrong is a silent offset that looks like "my model is floating".
 * So the mapping is written down here rather than discovered by experiment:
 *
 * <ul>
 *   <li>Java: {@code x,z ∈ [0,16]} with the block's minimum corner at the origin, and
 *       {@code y ∈ [0,16]} measured up from the bottom. Units are 1/16 of a block.</li>
 *   <li>Bedrock: {@code x,z ∈ [-8,8]} centred on the block, and {@code y ∈ [0,16]} up from
 *       the bottom, in the same 1/16 units. A full block is
 *       {@code origin [-8,0,-8], size [16,16,16]}.</li>
 * </ul>
 *
 * <p>So x and z are shifted by -8 and y is unchanged. The shift is applied to the cube
 * origin; the bone pivot stays at the block's bottom centre, {@code [0,0,0]}.
 *
 * <h2>Rotations are baked, and that is reported</h2>
 *
 * <p>Bedrock expresses element rotation with its own {@code rotation} block whose axis
 * sense differs from Java's. Rather than emit a rotation whose sign could be wrong, a
 * rotated element is emitted as the axis-aligned box that bounds it (the API already
 * computes this) and the result is marked {@link Result#lossy()} with a note. A bounded
 * box is never geometrically wrong -- it is a slightly larger shape in the rare case of an
 * angled element, and exact for the overwhelmingly common case of an unrotated one.
 */
public final class BedrockGeometryCompiler {

    /** Bedrock geometry format version Geyser accepts for custom blocks. */
    public static final String FORMAT_VERSION = "1.12.0";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** How far Java's block origin is from Bedrock's, in 1/16 units. */
    private static final double HORIZONTAL_ORIGIN_SHIFT = 8d;

    /**
     * The texture atlas size Java block models are authored against, in pixels.
     *
     * <p>A Java block model has no declaration of its texture size: 16x16 per block face
     * is the convention every model and every per-face {@code uv} rectangle is written
     * against. Bedrock requires the size to be stated in the geometry description, so it
     * is stated here as the same convention rather than invented per model.
     */
    private static final int MODEL_TEXTURE_UNITS = 16;

    private BedrockGeometryCompiler() {
    }

    /** A compiled geometry plus an honest account of anything that could not survive. */
    public record Result(String identifier, JsonObject document, boolean lossy, List<String> notes) {

        public Result {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(document, "document");
            notes = List.copyOf(notes);
        }

        /** The document as the JSON text that goes into a Bedrock resource pack. */
        public String json() {
            return GSON.toJson(document);
        }

        /** The identifier a block definition must reference. */
        public String reference() {
            return identifier;
        }

        /** One line for a startup log or a conversion report. */
        public String describe() {
            return identifier + (lossy ? " (lossy: " + String.join("; ", notes) + ")" : "");
        }
    }

    public static Result compile(CustomBlock block) {
        Objects.requireNonNull(block, "block");
        return compile(block.geometry(), "geometry.uas." + block.key().namespace() + "." + block.key().path());
    }

    /**
     * Compiles one geometry.
     *
     * @param identifier the Bedrock geometry identifier, e.g.
     *     {@code geometry.uas.example.crystal_lamp}
     */
    public static Result compile(BlockGeometry geometry, String identifier) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(identifier, "identifier");

        List<String> notes = new ArrayList<>();
        JsonArray cubes = new JsonArray();
        for (Cuboid cuboid : geometry.elements()) {
            cubes.add(cubeOf(cuboid, notes));
        }
        if (cubes.isEmpty()) {
            throw new IllegalArgumentException(
                "geometry '" + identifier + "' declares no elements; Bedrock needs at least one cube");
        }

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", MODEL_TEXTURE_UNITS);
        description.addProperty("texture_height", MODEL_TEXTURE_UNITS);
        // Visible bounds only affect the culling volume. staticBounds() is already in
        // block space (0..1 per axis), which is the unit Bedrock's visible bounds use, so
        // a tall model does not pop when the camera is close to it.
        double width = Math.max(1d, geometry.staticBounds().width());
        double height = Math.max(1d, geometry.staticBounds().height());
        description.addProperty("visible_bounds_width", width);
        description.addProperty("visible_bounds_height", height);
        JsonArray offset = new JsonArray();
        offset.add(0);
        offset.add(height / 2d);
        offset.add(0);
        description.add("visible_bounds_offset", offset);

        JsonObject bone = new JsonObject();
        bone.addProperty("name", "root");
        // The block's bottom centre: every cube origin above is relative to this.
        bone.add("pivot", vector(0, 0, 0));
        bone.add("cubes", cubes);

        JsonArray bones = new JsonArray();
        bones.add(bone);

        JsonObject model = new JsonObject();
        model.add("description", description);
        model.add("bones", bones);

        JsonArray models = new JsonArray();
        models.add(model);

        JsonObject document = new JsonObject();
        document.addProperty("format_version", FORMAT_VERSION);
        document.add("minecraft:geometry", models);

        // Animated textures are a pack-side concern; noting them here means a conversion
        // report points at what still has to be generated rather than silently dropping it.
        Map<String, String> animations = new LinkedHashMap<>();
        geometry.animatedTextures().forEach((key, animation) ->
            animations.put(key.toString(), animation.effectiveFrameTime() + "t"));
        if (!animations.isEmpty()) {
            notes.add("animated textures must be emissive/flipbook in the Bedrock pack: " + animations.keySet());
        }

        return new Result(identifier, document, !notes.isEmpty(), notes);
    }

    private static JsonObject cubeOf(Cuboid cuboid, List<String> notes) {
        JsonObject cube = new JsonObject();

        boolean rotated = cuboid.rotation() != null && !cuboid.rotation().isIdentity();
        var bounds = rotated ? cuboid.rotatedBounds() : cuboid.bounds();
        if (rotated) {
            notes.add("element at " + cuboid.from() + " is rotated; emitted as its bounding box");
        }

        cube.add("origin", vector(
            bounds.minX() - HORIZONTAL_ORIGIN_SHIFT,
            bounds.minY(),
            bounds.minZ() - HORIZONTAL_ORIGIN_SHIFT));
        cube.add("size", vector(bounds.width(), bounds.height(), bounds.depth()));

        Map<Face, FaceTexture> faces = cuboid.faces();
        if (faces.isEmpty() || allFacesShareOneUv(faces)) {
            // A single uv rectangle is what Bedrock expects for an automatically
            // unwrapped cube, and it is the smallest correct output.
            cube.add("uv", pair(0, 0));
        } else {
            // Per-face uv: the faithful form, used whenever the model declares distinct
            // rectangles per face (which is the normal case for a modelled block).
            cube.add("uv", perFaceUv(faces));
        }

        if (cuboid.shade()) {
            cube.addProperty("shade", true);
        }
        return cube;
    }

    private static boolean allFacesShareOneUv(Map<Face, FaceTexture> faces) {
        var first = faces.values().iterator().next().effectiveUv();
        for (FaceTexture texture : faces.values()) {
            if (!texture.effectiveUv().equals(first)) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject perFaceUv(Map<Face, FaceTexture> faces) {
        JsonObject byName = new JsonObject();
        for (Map.Entry<Face, FaceTexture> entry : faces.entrySet()) {
            var uv = entry.getValue().effectiveUv();
            JsonObject face = new JsonObject();
            face.add("uv", pair(uv.u1(), uv.v1()));
            face.add("uv_size", pair(uv.width(), uv.height()));
            if (entry.getValue().rotation() != 0) {
                face.addProperty("uv_rotation", entry.getValue().rotation());
            }
            byName.add(entry.getKey().bedrockName(), face);
        }
        return byName;
    }

    /** A two-element array, used by uv and uv_size. */
    private static JsonArray pair(double u, double v) {
        JsonArray array = new JsonArray();
        array.add(round(u));
        array.add(round(v));
        return array;
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray array = new JsonArray();
        // Rounded to 1/1000 to keep the emitted JSON stable and diffable: floating point
        // noise in a pack file makes every regeneration look like a change.
        array.add(round(x));
        array.add(round(y));
        array.add(round(z));
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
