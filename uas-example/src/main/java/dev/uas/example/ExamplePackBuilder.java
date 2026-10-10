package dev.uas.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.NamespacedKey;
import dev.uas.api.block.Cuboid;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.block.Face;
import dev.uas.api.block.FaceTexture;
import dev.uas.api.pack.ByteArrayAssetSource;
import dev.uas.api.pack.PackFormat;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.sound.SoundVariant;
import dev.uas.core.block.NoteBlockStateMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Java resource pack for the example content.
 *
 * <p>This is the pack the multiplexer streams over the Minecraft port, and it is where the
 * Java half of a custom block is actually realised: the client is told that
 * <em>this note-block state</em> draws <em>this model</em>. Without it the block exists in
 * the world and renders as a plain note block.
 *
 * <h2>Why the blockstate file lives in the {@code minecraft} namespace</h2>
 *
 * <p>The vanilla note block is defined by {@code assets/minecraft/blockstates/note_block.json}.
 * Adding a variant to a vanilla block means shipping a file at that same path -- a pack
 * cannot add variants to a vanilla block from its own namespace. The custom <em>models</em>
 * and textures stay in the engine's namespace, which is what keeps them addressable and
 * collision-free.
 *
 * <p>The state used is the one {@link NoteBlockStateMapper} assigns, the same allocator the
 * Paper side uses to place the block. If those two ever disagreed, every placed lamp would
 * render as a default note block and the only diagnostic would be a player saying "it looks
 * wrong", so they share one implementation rather than two.
 */
public final class ExamplePackBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The vanilla namespace, needed because the blockstate override is a vanilla file. */
    public static final String VANILLA_NAMESPACE = "minecraft";

    private ExamplePackBuilder() {
    }

    /** The entries a pack for this content must contain. */
    public static List<ByteArrayAssetSource> entries(CustomBlock block, List<CustomSound> sounds) {
        Map<NamespacedKey, NoteBlockStateMapper.State> states =
            NoteBlockStateMapper.allocate(List.of(block.key()));
        NoteBlockStateMapper.State state = states.get(block.key());

        List<ByteArrayAssetSource> entries = new ArrayList<>();
        entries.add(ByteArrayAssetSource.utf8("pack.mcmeta", packMcmeta()));

        // The variant that binds the engine's note-block state to the custom model.
        JsonObject variants = new JsonObject();
        JsonObject modelRef = new JsonObject();
        modelRef.addProperty("model", block.key().namespace() + ":block/" + block.key().path());
        variants.add(state.blockStateString(), modelRef);
        JsonObject blockstates = new JsonObject();
        blockstates.add("variants", variants);
        entries.add(ByteArrayAssetSource.utf8(
            "assets/" + VANILLA_NAMESPACE + "/blockstates/note_block.json", GSON.toJson(blockstates)));

        entries.add(ByteArrayAssetSource.utf8(
            "assets/" + block.key().namespace() + "/models/block/" + block.key().path() + ".json",
            GSON.toJson(blockModel(block))));

        // The item model is what a player sees in the inventory: a flat icon referencing the
        // same textures, which is why it can be generated rather than authored.
        entries.add(ByteArrayAssetSource.utf8(
            "assets/" + block.key().namespace() + "/models/item/" + block.key().path() + ".json",
            GSON.toJson(itemModel(block))));

        entries.add(ByteArrayAssetSource.utf8(
            "assets/" + block.key().namespace() + "/sounds.json", GSON.toJson(soundDefinitions(sounds))));

        // Animated textures are declared by a sidecar .mcmeta next to the texture, so the
        // animation the block declares is only real if this file exists.
        block.geometry().animatedTextures().forEach((texture, animation) ->
            entries.add(ByteArrayAssetSource.utf8(
                "assets/" + texture.namespace() + "/textures/" + texture.path() + ".png.mcmeta",
                GSON.toJson(animationMcmeta(animation)))));

        return entries;
    }

    /**
     * The texture files a pack for this content must ship.
     *
     * <p>Separated from {@link #entries} and deliberately not generated: a placeholder PNG
     * is an invalid image and produces a missing-texture block, whereas a named requirement
     * is something an operator can act on.
     */
    public static List<String> requiredTextures(CustomBlock block, List<CustomSound> sounds) {
        List<String> assets = new ArrayList<>();
        for (NamespacedKey texture : block.geometry().referencedTextures()) {
            assets.add("assets/" + texture.namespace() + "/textures/" + texture.path() + ".png");
        }
        for (CustomSound sound : sounds) {
            for (SoundVariant variant : sound.variants()) {
                // assetPath() is "<namespace>/sounds/<path>.ogg", so the pack path is
                // assets/<namespace>/sounds/<path>.ogg -- the leading namespace is not a
                // directory, which is why it is not concatenated blindly.
                assets.add("assets/" + variant.key().namespace() + "/sounds/"
                    + variant.key().path() + ".ogg");
            }
        }
        return List.copyOf(assets);
    }

    /** {@code pack.mcmeta}, using the shared format model rather than a hard-coded number. */
    public static String packMcmeta() {
        PackFormat format = PackFormat.javaDefault();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", format.format());
        pack.add("supported_formats", GSON.toJsonTree(format.asJsonValue()));
        pack.addProperty("description", "UAS example content");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return GSON.toJson(root);
    }

    /** A Java block model built straight from the declared geometry. */
    static JsonObject blockModel(CustomBlock block) {
        JsonObject model = new JsonObject();
        // The vanilla block parent supplies the display transforms a block model needs.
        model.addProperty("parent", "block/block");

        JsonObject textures = new JsonObject();
        block.geometry().textures().forEach((slot, reference) -> textures.addProperty(slot, reference));
        // A particle texture is required: without it breaking particles are the missing
        // texture, which is the most visible symptom of an otherwise correct model.
        if (!textures.has("particle") && !block.geometry().textures().isEmpty()) {
            textures.addProperty("particle", particleTexture(block));
        }
        model.add("textures", textures);

        JsonArray elements = new JsonArray();
        for (Cuboid cuboid : block.geometry().elements()) {
            elements.add(element(cuboid));
        }
        model.add("elements", elements);
        if (block.geometry().renderLayer().isTransparent()) {
            model.addProperty("ambientocclusion", block.geometry().ambientOcclusion());
        }
        return model;
    }

    /**
     * The texture breaking particles use when the block declares no {@code particle} slot.
     *
     * <p>Taken from the up face of the topmost element, and resolved through the geometry's own
     * slot resolution so that a {@code #slot} reference is followed to a real texture. The top
     * face is what a player sees when particles spawn, and tying the choice to the geometry
     * makes the model a function of what was declared rather than of whatever order the texture
     * map happened to iterate in -- picking the first entry made the compiled archive differ
     * between two identical servers.
     */
    private static String particleTexture(CustomBlock block) {
        Cuboid topmost = null;
        double highestTop = Double.NEGATIVE_INFINITY;
        for (Cuboid cuboid : block.geometry().elements()) {
            // Elements without an up face cannot supply a top surface; the list itself is a
            // stable copy, so an equal top resolves to the first element declared.
            if (cuboid.faces().get(Face.UP) == null) {
                continue;
            }
            if (topmost == null || cuboid.to().y() > highestTop) {
                topmost = cuboid;
                highestTop = cuboid.to().y();
            }
        }
        if (topmost != null) {
            // A misdeclared slot throws here rather than shipping a model with a missing
            // texture, which is the stance the geometry itself takes.
            return block.geometry().resolveTexture(topmost.faces().get(Face.UP)).toString();
        }
        // No element declares an up face at all: fall back to the first declared slot, which is
        // still stable, because BlockGeometry preserves declaration order.
        return block.geometry().resolveTexture(
            FaceTexture.of("#" + block.geometry().textures().keySet().iterator().next())).toString();
    }

    private static JsonObject element(Cuboid cuboid) {
        JsonObject element = new JsonObject();
        // Java models use the same 0..16 space the API declares in, so no conversion.
        element.add("from", vector(cuboid.from().x(), cuboid.from().y(), cuboid.from().z()));
        element.add("to", vector(cuboid.to().x(), cuboid.to().y(), cuboid.to().z()));

        JsonObject faces = new JsonObject();
        for (Map.Entry<Face, FaceTexture> entry : cuboid.faces().entrySet()) {
            JsonObject face = new JsonObject();
            face.addProperty("texture", entry.getValue().texture());
            var uv = entry.getValue().effectiveUv();
            face.add("uv", vector(uv.u1(), uv.v1(), uv.u2(), uv.v2()));
            if (entry.getValue().rotation() != 0) {
                face.addProperty("rotation", entry.getValue().rotation());
            }
            if (entry.getValue().cullFace() != null) {
                face.addProperty("cullface", entry.getValue().cullFace().bedrockName());
            }
            if (entry.getValue().tintIndex() != null) {
                face.addProperty("tintindex", entry.getValue().tintIndex());
            }
            faces.add(entry.getKey().bedrockName(), face);
        }
        element.add("faces", faces);
        if (cuboid.shade()) {
            element.addProperty("shade", true);
        }
        if (cuboid.rotation() != null && !cuboid.rotation().isIdentity()) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", vector(
                cuboid.rotation().origin().x(),
                cuboid.rotation().origin().y(),
                cuboid.rotation().origin().z()));
            rotation.addProperty("x", cuboid.rotation().x());
            rotation.addProperty("y", cuboid.rotation().y());
            rotation.addProperty("z", cuboid.rotation().z());
            rotation.addProperty("rescale", cuboid.rotation().rescale());
            element.add("rotation", rotation);
        }
        return element;
    }

    /** A flat item model: one quad using the block's primary texture. */
    static JsonObject itemModel(CustomBlock block) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", "item/generated");
        JsonObject textures = new JsonObject();
        String primary = block.itemModel() == null
            ? block.key().namespace() + ":item/" + block.key().path()
            : block.itemModel();
        String layer0 = primary.contains(":item/") ? primary.replace(":item/", ":block/") : primary;
        textures.addProperty("layer0", layer0);
        model.add("textures", textures);
        return model;
    }

    /**
     * Java's {@code sounds.json}.
     *
     * <p>Keeps the weighted variant list exactly as declared. This is the file Bedrock cannot
     * represent directly, so writing it faithfully here is what makes the Bedrock compiler's
     * expansion a real translation rather than a shared loss.
     */
    static JsonObject soundDefinitions(List<CustomSound> sounds) {
        JsonObject definitions = new JsonObject();
        for (CustomSound sound : sounds) {
            JsonObject definition = new JsonObject();
            definition.addProperty("category", sound.category().javaName());
            if (!sound.subtitle().isEmpty()) {
                definition.addProperty("subtitle", sound.subtitle());
            }
            JsonArray variants = new JsonArray();
            for (SoundVariant variant : sound.variants()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", variant.key().toString());
                entry.addProperty("stream", variant.stream());
                entry.addProperty("weight", variant.weight());
                entry.addProperty("volume", variant.volume());
                if (variant.pitchMin() != variant.pitchMax()) {
                    entry.addProperty("pitch", variant.pitchMin());
                    entry.addProperty("pitch_range", variant.pitchMax());
                }
                variants.add(entry);
            }
            definition.add("sounds", variants);
            if (sound.replaceExisting()) {
                definition.addProperty("replace", true);
            }
            definitions.add(sound.key().toString(), definition);
        }
        return definitions;
    }

    private static JsonObject animationMcmeta(dev.uas.api.block.AnimatedTexture animation) {
        JsonObject animationObject = new JsonObject();
        animationObject.addProperty("frametime", animation.effectiveFrameTime());
        animationObject.addProperty("interpolate", animation.interpolate());
        JsonArray frames = new JsonArray();
        for (int frame : animation.effectiveFrameOrder()) {
            frames.add(frame);
        }
        animationObject.add("frames", frames);
        JsonObject root = new JsonObject();
        root.add("animation", animationObject);
        return root;
    }

    private static JsonArray vector(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(Math.round(value * 1000d) / 1000d);
        }
        return array;
    }

    /** A stable map of the generated pack files, for a startup log. */
    public static Map<String, String> manifest(CustomBlock block, List<CustomSound> sounds) {
        Map<String, String> files = new LinkedHashMap<>();
        for (ByteArrayAssetSource entry : entries(block, sounds)) {
            files.put(entry.path(), entry.size() + " bytes");
        }
        return Map.copyOf(files);
    }
}
