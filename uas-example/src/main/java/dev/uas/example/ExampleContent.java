package dev.uas.example;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.AnimatedTexture;
import dev.uas.api.block.BlockGeometry;
import dev.uas.api.block.BlockSoundGroup;
import dev.uas.api.block.Cuboid;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.block.Face;
import dev.uas.api.block.FaceTexture;
import dev.uas.api.block.RenderLayer;
import dev.uas.api.math.Vec3;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.sound.SoundCategory;
import dev.uas.api.sound.SoundVariant;
import dev.uas.api.ui.Anchor;
import dev.uas.api.ui.Size;
import dev.uas.api.ui.UiAction;
import dev.uas.api.ui.UiElement;
import dev.uas.api.ui.UiPresentation;
import dev.uas.api.ui.UiSchema;
import dev.uas.api.ui.UiStyle;
import java.util.List;

/**
 * The declarations the example plugin registers, written as pure code.
 *
 * <p>Separating them from the plugin class is not tidiness. A declaration is the only
 * thing in the whole engine that has to be simultaneously correct for two renderers and
 * three backends, and it is the part most likely to be wrong. Kept pure it can be asserted
 * in a plain unit test -- is the animated texture declared, is the collision box the one
 * the block's geometry implies, does the overlay bind a key the schema declares -- instead
 * of being discoverable only by running a server.
 *
 * <p>Everything here is deliberately non-trivial, because a hello-world declaration would
 * exercise nothing: the block is shaped (so it needs geometry rather than a full cube), it
 * is animated (so it needs a {@code .mcmeta} and a Bedrock flipbook), it is translucent in
 * part (so the render layer has to survive both conversions), it emits light (so the
 * display-entity backend's advantage over the note-block matrix is visible), and it uses
 * five distinct sounds rather than one.
 */
public final class ExampleContent {

    /** Namespace for everything the example declares. */
    public static final String NAMESPACE = "uas";

    public static final NamespacedKey BLOCK_KEY = NamespacedKey.parse("uas:crystal_lamp");
    public static final NamespacedKey HUD_KEY = NamespacedKey.parse("uas:charge_hud");

    /**
     * The bound state key the HUD reads and writes.
     *
     * <p>A plain string, not a namespaced key: state keys name a slot in one schema's state
     * map rather than a globally addressable thing, and the schema validator rejects any key
     * the tree does not actually bind.
     */
    public static final String UI_STATE_KEY = "charge";

    /** The animation frame size, in pixels, of the lamp's crystal texture. */
    public static final int ANIMATION_FRAME_SIZE = 16;
    /** Frames in the crystal flicker. */
    public static final int ANIMATION_FRAMES = 8;
    /** Ticks each frame is held. */
    public static final int ANIMATION_FRAME_TICKS = 4;

    private ExampleContent() {
    }

    /**
     * The animated lamp.
     *
     * <p>Two elements rather than one: a solid base and a translucent crystal body. That is
     * the smallest declaration that forces every conversion to make a decision -- the base
     * is a cutout-free solid, the crystal is translucently blended, so a pack that applied
     * one render layer to the whole block would be visibly wrong.
     */
    public static CustomBlock crystalLamp() {
        NamespacedKey crystalTexture = NamespacedKey.parse("uas:block/crystal_lamp_crystal");
        NamespacedKey baseTexture = NamespacedKey.parse("uas:block/crystal_lamp_base");

        BlockGeometry geometry = BlockGeometry.builder()
            // Base: a squat plinth occupying the bottom quarter of the block.
            //
            // The leading '#' is not decoration: a face texture without it is a concrete
            // texture *path*, so "base" would be read by the client as minecraft:base and
            // every face would render as the missing-texture pattern. Only "#base" names the
            // slot declared below.
            .element(Cuboid.builder(1, 0, 1, 15, 4, 15)
                .allFaces("#base")
                .build())
            // Crystal body: inset on all sides, from the base up to the top of the block.
            .element(Cuboid.builder(4, 4, 4, 12, 15, 12)
                .allFaces("#crystal")
                // A textured top face so the crystal reads as hollow rather than as a box.
                .face(Face.UP, FaceTexture.of("#crystal"))
                .build())
            .texture("base", baseTexture.toString())
            .texture("crystal", crystalTexture.toString())
            // Translucent, because the crystal is a translucent material. Declaring this once
            // is what keeps the Java pack's render layer and Bedrock's render_method in sync.
            .renderLayer(RenderLayer.TRANSLUCENT)
            .animation(AnimatedTexture.flipbook(
                crystalTexture, ANIMATION_FRAME_SIZE, ANIMATION_FRAMES, ANIMATION_FRAME_TICKS))
            .build();

        return CustomBlock.builder(BLOCK_KEY)
            .displayName("Crystal Lamp")
            .geometry(geometry)
            .sounds(lampSounds())
            .physics(dev.uas.api.block.BlockPhysics.builder()
                // A full-cube collider: the crystal is inset, but a block you can walk into
                // at the corners is worse than a slightly generous collider.
                .collisionBox(dev.uas.api.block.BoundingBox.full())
                .selectionBox(dev.uas.api.block.BoundingBox.full())
                .hardness(1.5f)
                .blastResistance(6f)
                // The reason this example prefers the display-entity backend: a note block
                // matrix cannot emit light, so on a server without display entities this
                // value is reported as unsupported rather than silently ignored.
                .lightEmission(12)
                .solid(true)
                .requiresTool(false)
                .build())
            .itemModel(NamespacedKey.parse("uas:item/crystal_lamp").toString())
            .build();
    }

    /** Five distinct sounds, so the group is exercised rather than a single uniform key. */
    public static BlockSoundGroup lampSounds() {
        return BlockSoundGroup.of(
            NamespacedKey.parse("uas:block/crystal_lamp_step"),
            NamespacedKey.parse("uas:block/crystal_lamp_break"),
            NamespacedKey.parse("uas:block/crystal_lamp_place"),
            NamespacedKey.parse("uas:block/crystal_lamp_hit"),
            NamespacedKey.parse("uas:block/crystal_lamp_fall"))
            .withVolume(0.8f)
            .withPitch(1.2f);
    }

    /**
     * The lamp's sound events.
     *
     * <p>The break sound deliberately has two variants with unequal weights. That is the one
     * property Bedrock cannot express directly, so it is the property whose conversion has to
     * be visible in the report -- a single-variant sound would make the whole expansion path
     * untested.
     */
    public static List<CustomSound> lampSoundEvents() {
        return List.of(
            CustomSound.builder(NamespacedKey.parse("uas:block/crystal_lamp_break"))
                .variant(SoundVariant.of(NamespacedKey.parse("uas:block/crystal_lamp_break_1"), 3))
                .variant(SoundVariant.of(NamespacedKey.parse("uas:block/crystal_lamp_break_2"), 1))
                .subtitle("Crystal shatters")
                .category(SoundCategory.BLOCKS)
                .build(),
            CustomSound.builder(NamespacedKey.parse("uas:block/crystal_lamp_step"))
                .file(NamespacedKey.parse("uas:block/crystal_lamp_step"))
                .subtitle("Crystal chimes")
                .category(SoundCategory.BLOCKS)
                .build(),
            CustomSound.builder(NamespacedKey.parse("uas:block/crystal_lamp_place"))
                .file(NamespacedKey.parse("uas:block/crystal_lamp_place"))
                .subtitle("Crystal placed")
                .category(SoundCategory.BLOCKS)
                .build(),
            CustomSound.builder(NamespacedKey.parse("uas:block/crystal_lamp_hit"))
                .file(NamespacedKey.parse("uas:block/crystal_lamp_hit"))
                .subtitle("Crystal rings")
                .category(SoundCategory.BLOCKS)
                .build(),
            CustomSound.builder(NamespacedKey.parse("uas:block/crystal_lamp_fall"))
                .file(NamespacedKey.parse("uas:block/crystal_lamp_fall"))
                .category(SoundCategory.BLOCKS)
                .build(),
            CustomSound.builder(NamespacedKey.parse("uas:ui/charge_ping"))
                .file(NamespacedKey.parse("uas:ui/charge_ping"))
                .category(SoundCategory.UI)
                .build());
    }

    /**
     * A HUD overlay bound to charge state, plus a button that writes to it.
     *
     * <p>An {@code OVERLAY} rather than a modal, because that is the presentation Bedrock
     * cannot show natively. Compiling it forces the interesting path: Bedrock either shows a
     * modal dialog instead (a degradation the plan reports) or gets custom UI JSON from the
     * pack, depending on what the author allowed.
     */
    public static UiSchema chargeHud() {
        return UiSchema.builder(HUD_KEY)
            .title("Charge")
            .presentation(UiPresentation.OVERLAY)
            // The tree is form-expressible except for the progress bar, so the planner will
            // route Bedrock to custom UI JSON. Declaring no fallback makes that explicit.
            .bedrockModalFallback(false)
            .initialValue(UI_STATE_KEY, "0")
            .cancellable(false)
            .add(UiElement.Text.of("label", "Charge: {charge}"))
            .add(UiElement.ProgressBar.of("bar", UI_STATE_KEY, 100))
            // A button that writes back to the same bound key, so the client-to-server state
            // path exists rather than being described.
            .add(new UiElement.Button(
                "release",
                UiStyle.builder()
                    .anchor(Anchor.BOTTOM_CENTER)
                    .size(Size.px(100), Size.px(20))
                    .action(UiAction.setState(UI_STATE_KEY, "0"))
                    .build(),
                "Release",
                null,
                null,
                "release"))
            .build();
    }

    /**
     * The cue played when a player interacts with a placed lamp.
     *
     * <p>A {@code SoundRef} rather than a bare key, because this is what the cross-platform
     * packet carries: key, volume, pitch, category and attenuation in one value, so a Bedrock
     * client is told how loud and how far the sound travels without a second lookup table.
     */
    public static dev.uas.api.block.SoundRef interactionCue() {
        return dev.uas.api.block.SoundRef.blocks(
            NamespacedKey.parse("uas:block/crystal_lamp_hit"), 0.7f, 1.4f);
    }

    /** The point inside the block the interaction cue is emitted from, in block space. */
    public static Vec3 interactionOrigin() {
        return new Vec3(0.5, 0.9, 0.5);
    }
}
