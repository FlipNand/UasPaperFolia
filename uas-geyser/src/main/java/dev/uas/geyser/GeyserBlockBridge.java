package dev.uas.geyser;

import dev.uas.api.block.BoundingBox;
import dev.uas.api.block.CustomBlock;
import dev.uas.geyser.jit.BedrockBlockDefinitionCompiler;
import dev.uas.geyser.jit.JitBlockConverter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.geysermc.geyser.api.block.custom.NonVanillaCustomBlockData;
import org.geysermc.geyser.api.block.custom.component.BoxComponent;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.GeometryComponent;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.util.CreativeCategory;

/**
 * Registers the catalog with Geyser's custom-block API.
 *
 * <p>This is the thin, Geyser-typed layer; every numeric decision it makes comes from
 * {@link BedrockBlockDefinitionCompiler}, which is pure and tested. That split is
 * deliberate: Geyser's API cannot be instantiated in a unit test, so any conversion logic
 * living here would be untestable by construction, and the conversions are exactly the part
 * that has to be right.
 *
 * <p>The bridge reports rather than hides the conversions that lose information: a
 * collision set that Bedrock must approximate, a distortion that a non-unit-cube geometry
 * implies, a sound that could not be weighted. A conversion that quietly drops a detail is
 * indistinguishable, from the player's side, from a bug.
 */
public final class GeyserBlockBridge {

    private GeyserBlockBridge() {
    }

    /** What registration produced, for a startup log. */
    public record Registered(int blocks, List<String> notes) {

        public Registered {
            notes = List.copyOf(notes);
        }

        public boolean lossy() {
            return !notes.isEmpty();
        }
    }

    /**
     * Registers every block in the catalog on the event.
     *
     * <p>Called from Geyser's {@code GeyserDefineCustomBlocksEvent}, which is the only
     * moment custom blocks may be declared. Registering later is not an option: the block
     * set is part of what the client is told at login, so a block added afterwards is
     * invisible until the player reconnects.
     */
    public static Registered registerAll(
        GeyserDefineCustomBlocksEvent event, Collection<CustomBlock> blocks) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(blocks, "blocks");

        List<String> notes = new ArrayList<>();
        int registered = 0;
        for (CustomBlock block : blocks) {
            try {
                event.register(toBedrock(block, notes));
                registered++;
            } catch (RuntimeException failure) {
                // One bad block must not take the whole Bedrock block set down with it:
                // Geyser would then fall back to vanilla blocks for every custom block.
                notes.add("block '" + block.key() + "' could not be registered: " + failure);
            }
        }
        return new Registered(registered, notes);
    }

    /**
     * Builds the Bedrock block data for one custom block.
     *
     * <p>{@code NonVanilla} rather than vanilla-backed: the whole point is a block Bedrock
     * has never heard of, identified by the engine's own namespace, with its own geometry
     * and components. The vanilla-backed variant exists for overriding an existing Bedrock
     * block, which is not what a custom block is.
     */
    public static NonVanillaCustomBlockData toBedrock(CustomBlock block, List<String> notes) {
        Objects.requireNonNull(block, "block");

        // The same conversion the pack writer uses, so Geyser is told about exactly the
        // geometry and components the client will receive. Compiling them separately here
        // would let the registered block and the shipped model disagree.
        JitBlockConverter.Conversion conversion =
            JitBlockConverter.convert(block, UasCatalog.geometryIdentifier(block));
        notes.addAll(conversion.notes());
        String geometryIdentifier = conversion.geometryIdentifier();

        var physics = block.physics();
        CustomBlockComponents.Builder components = CustomBlockComponents.builder()
            .displayName(block.displayName())
            .geometry(GeometryComponent.builder().identifier(geometryIdentifier).build())
            .materialInstance("*", MaterialInstance.builder()
                .texture(BedrockBlockDefinitionCompiler.terrainTextureKey(block))
                .renderMethod(block.geometry().renderLayer().bedrockRenderMethod())
                .build())
            .destructibleByMining((float) BedrockBlockDefinitionCompiler.miningSeconds(physics.hardness()))
            .friction(physics.friction());
        // No unitCube() call: that setter is deprecated in this API line, and Geyser now
        // derives the cube-ness from the geometry component it was handed, so setting it
        // explicitly is redundant as well as noisy.

        if (physics.lightEmission() > 0) {
            components.lightEmission(physics.lightEmission());
        }
        if (physics.hasCollision()) {
            if (!physics.hasSingleCollisionBox()) {
                notes.add("block '" + block.key() + "' declares " + physics.collisionBoxes().size()
                    + " collision boxes; Bedrock's collision component takes one box set");
            }
            List<BoxComponent> boxes = new ArrayList<>();
            for (BoundingBox box : physics.collisionBoxes().isEmpty()
                ? List.of(physics.collisionUnion())
                : physics.collisionBoxes()) {
                boxes.add(toBox(box));
            }
            components.collisionBoxes(boxes);
        }
        if (!physics.selectionBoxes().isEmpty()) {
            components.selectionBox(unionOf(physics.selectionBoxes()));
        }
        if (!block.geometry().isUnitCube()) {
            notes.add("block '" + block.key() + "' is not a unit cube; Bedrock renders its own "
                + "geometry while the Java side uses a display entity, so the two shapes come "
                + "from the same declaration but different renderers");
        }

        return NonVanillaCustomBlockData.builder()
            .namespace(block.key().namespace())
            .name(block.key().path())
            .includedInCreativeInventory(block.includeInCreativeInventory())
            .creativeCategory(creativeCategory(block))
            .creativeGroup(block.creativeGroup())
            .components(components.build())
            .build();
    }

    /**
     * Maps a declared creative category onto Geyser's enum.
     *
     * <p>Falls back to {@code CONSTRUCTION} rather than throwing: an unrecognised category
     * is a cosmetic problem, and refusing to register the block over it would be a
     * gameplay problem.
     */
    static CreativeCategory creativeCategory(CustomBlock block) {
        String declared = block.creativeCategory() == null ? "" : block.creativeCategory();
        for (CreativeCategory category : CreativeCategory.values()) {
            if (category.name().equalsIgnoreCase(declared)) {
                return category;
            }
        }
        return CreativeCategory.CONSTRUCTION;
    }

    private static BoxComponent toBox(BoundingBox bounds) {
        // BoundingBox is in block space (0..1, origin at the block's minimum corner);
        // Bedrock's BoxComponent is in 1/16 units with x and z centred on the block.
        return new BoxComponent(
            (float) (bounds.minX() * 16d - 8d),
            (float) (bounds.minY() * 16d),
            (float) (bounds.minZ() * 16d - 8d),
            (float) (bounds.width() * 16d),
            (float) (bounds.height() * 16d),
            (float) (bounds.depth() * 16d));
    }

    private static BoxComponent unionOf(List<BoundingBox> boxes) {
        BoundingBox union = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            union = union.union(boxes.get(i));
        }
        return toBox(union);
    }
}
