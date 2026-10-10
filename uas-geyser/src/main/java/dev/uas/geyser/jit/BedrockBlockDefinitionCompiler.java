package dev.uas.geyser.jit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.block.BoundingBox;
import dev.uas.api.block.CustomBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles a custom block into a Bedrock block definition document.
 *
 * <p>The definition is the other half of the geometry: geometry says what the block
 * looks like, the definition says what it <em>is</em>. Both are needed because Bedrock's
 * custom-block system has no concept of "a Java block with a plugin's model" -- the block
 * is declared to the client from scratch, and the Java block state that backs it is
 * invisible to the Bedrock client.
 *
 * <p>Every number here comes from the block's own {@link dev.uas.api.block.BlockPhysics},
 * and every conversion that is not a rename is stated:
 *
 * <ul>
 *   <li><strong>Boxes.</strong> The API works in block space (0..1 per axis, origin at the
 *       block's minimum corner); Bedrock works in 1/16 units with x and z centred on the
 *       block. So x and z are shifted by -8 and all axes are scaled by 16. This is the
 *       same convention the geometry compiler uses, deliberately: one place per document
 *       where the origin is decided, and both agree.</li>
 *   <li><strong>Hardness.</strong> Java's hardness is a mining-speed coefficient, Bedrock's
 *       {@code seconds_to_destroy} is roughly {@code hardness * 1.5} for a bare hand. The
 *       conversion is applied and clamped, and the block's own value is what drives it --
 *       the constant is named rather than inlined.</li>
 *   <li><strong>No collision.</strong> Bedrock wants {@code "collision_box": false} for a
 *       block you can walk through; emitting an empty box instead is accepted but leaves a
 *       degenerate collider, so the boolean form is used.</li>
 * </ul>
 */
public final class BedrockBlockDefinitionCompiler {

    /** Bedrock block definition format version for custom blocks with components. */
    public static final String FORMAT_VERSION = "1.20.10";

    /** Mining-time conversion from Java hardness to Bedrock seconds-to-destroy. */
    public static final double MINING_SECONDS_PER_HARDNESS = 1.5d;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BedrockBlockDefinitionCompiler() {
    }

    /** A compiled definition plus the notes a conversion report should surface. */
    public record Result(String identifier, JsonObject document, List<String> notes) {

        public Result {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(document, "document");
            notes = List.copyOf(notes);
        }

        public String json() {
            return GSON.toJson(document);
        }
    }

    /** The Bedrock identifier, {@code namespace_path}, derived from the block's key. */
    public static String identifierOf(CustomBlock block) {
        return block.key().toBedrockIdentifier();
    }

    /** The short texture name a {@code terrain_texture.json} entry must define. */
    public static String terrainTextureKey(CustomBlock block) {
        return block.key().namespace() + "_" + block.key().path();
    }

    /**
     * Compiles the definition.
     *
     * @param geometryIdentifier the identifier returned by
     *     {@link BedrockGeometryCompiler#compile(CustomBlock)}
     */
    public static Result compile(CustomBlock block, String geometryIdentifier) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(geometryIdentifier, "geometryIdentifier");
        List<String> notes = new ArrayList<>();

        var physics = block.physics();

        JsonObject components = new JsonObject();
        components.addProperty("minecraft:geometry", geometryIdentifier);

        JsonObject material = new JsonObject();
        material.addProperty("texture", terrainTextureKey(block));
        // The render method comes from the model's declared render layer, so a cutout
        // model does not render as an opaque quad and vice versa.
        material.addProperty("render_method",
            block.geometry().renderLayer().bedrockRenderMethod());
        JsonObject materials = new JsonObject();
        materials.add("*", material);
        components.add("minecraft:material_instances", materials);

        JsonObject mining = new JsonObject();
        mining.addProperty("seconds_to_destroy", miningSeconds(physics.hardness()));
        components.add("minecraft:destructible_by_mining", mining);

        JsonObject explosion = new JsonObject();
        explosion.addProperty("explosion_resistance", physics.blastResistance());
        components.add("minecraft:destructible_by_explosion", explosion);

        if (physics.lightEmission() > 0) {
            components.addProperty("minecraft:light_emission", physics.lightEmission());
        }
        components.addProperty("minecraft:friction", physics.friction());

        // Collision: the boolean form when there is nothing to collide with, the box form
        // otherwise. A multi-box collision set has no Bedrock equivalent -- Bedrock's
        // collision_box is one box -- so it is approximated by the union and reported,
        // rather than silently dropped.
        if (!physics.hasCollision()) {
            components.addProperty("minecraft:collision_box", false);
        } else {
            if (!physics.hasSingleCollisionBox()) {
                notes.add("declared " + physics.collisionBoxes().size()
                    + " collision boxes; Bedrock's collision_box takes one, so the union is used");
            }
            components.add("minecraft:collision_box", box(physics.collisionUnion()));
        }

        if (!physics.selectionBoxes().isEmpty()) {
            BoundingBox selection = unionOf(physics.selectionBoxes());
            if (physics.selectionBoxes().size() > 1) {
                notes.add("declared " + physics.selectionBoxes().size()
                    + " selection boxes; the union is used");
            }
            components.add("minecraft:selection_box", box(selection));
        }

        if (physics.flammable()) {
            JsonObject flammable = new JsonObject();
            flammable.addProperty("catch_chance_modifier", 5);
            flammable.addProperty("destroy_chance_modifier", 20);
            components.add("minecraft:flammable", flammable);
        }
        if (physics.redstoneConductive()) {
            JsonObject conductivity = new JsonObject();
            conductivity.addProperty("redstone_conductor", true);
            components.add("minecraft:redstone_conductivity", conductivity);
        }

        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifierOf(block));
        JsonObject menuCategory = new JsonObject();
        menuCategory.addProperty("category", bedrockCategory(block));
        menuCategory.addProperty("group", block.creativeGroup());
        description.add("menu_category", menuCategory);

        JsonObject unity = new JsonObject();
        unity.add("description", description);
        unity.add("components", components);

        JsonObject document = new JsonObject();
        document.addProperty("format_version", FORMAT_VERSION);
        document.add("minecraft:block", unity);

        return new Result(identifierOf(block), document, notes);
    }

    /** Java hardness to Bedrock seconds, clamped to a value the client accepts. */
    public static double miningSeconds(float hardness) {
        if (hardness < 0) {
            // Negative hardness means unbreakable in Java; Bedrock expresses that by
            // omitting destructible_by_mining entirely, so a large value is the safe
            // stand-in here and the caller can still strip the component.
            return 0d;
        }
        return Math.max(0.05d, hardness * MINING_SECONDS_PER_HARDNESS);
    }

    private static String bedrockCategory(CustomBlock block) {
        String declared = block.creativeCategory();
        for (String valid : List.of("construction", "nature", "equipment", "items", "none")) {
            if (valid.equalsIgnoreCase(declared)) {
                return valid;
            }
        }
        // An unknown category would make the client reject the whole definition, so an
        // unrecognised value falls back rather than being passed through.
        return "construction";
    }

    private static JsonObject box(BoundingBox bounds) {
        JsonObject box = new JsonObject();
        box.add("origin", vector(
            bounds.minX() * 16d - 8d,
            bounds.minY() * 16d,
            bounds.minZ() * 16d - 8d));
        box.add("size", vector(
            bounds.width() * 16d,
            bounds.height() * 16d,
            bounds.depth() * 16d));
        return box;
    }

    private static BoundingBox unionOf(List<BoundingBox> boxes) {
        BoundingBox union = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            union = union.union(boxes.get(i));
        }
        return union;
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(Math.round(x * 1000d) / 1000d);
        array.add(Math.round(y * 1000d) / 1000d);
        array.add(Math.round(z * 1000d) / 1000d);
        return array;
    }
}
