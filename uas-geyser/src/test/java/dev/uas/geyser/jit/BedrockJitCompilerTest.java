package dev.uas.geyser.jit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockGeometry;
import dev.uas.api.block.BlockSoundGroup;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.block.RenderLayer;
import dev.uas.api.block.Rotation;
import dev.uas.api.sound.AttenuationCurve;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.sound.SoundVariant;
import dev.uas.api.ui.Size;
import dev.uas.api.ui.UiAction;
import dev.uas.api.ui.UiElement;
import dev.uas.api.ui.UiPresentation;
import dev.uas.api.ui.UiSchema;
import dev.uas.core.ui.UiRenderPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The conversions are asserted on exact numbers, because a coordinate mapping that is off
 * by eight units produces a model that renders a half-block away from its own collision box
 * and looks like a client bug rather than a converter bug.
 */
class BedrockJitCompilerTest {

    private static CustomBlock block(String path, BlockGeometry geometry) {
        return CustomBlock.builder(NamespacedKey.parse("uas:" + path))
            .displayName(path)
            .geometry(geometry)
            .sounds(BlockSoundGroup.uniform(NamespacedKey.parse("uas:" + path + "_step")))
            .build();
    }

    private static BlockGeometry fullCube(RenderLayer layer) {
        return BlockGeometry.builder()
            .fullCube(NamespacedKey.parse("uas:block/test"))
            .renderLayer(layer)
            .build();
    }

    // ------------------------------------------------------------------- geometry

    @Test
    void aFullCubeBecomesTheCanonicalCentredBedrockCube() {
        BedrockGeometryCompiler.Result result =
            BedrockGeometryCompiler.compile(fullCube(RenderLayer.SOLID), "geometry.uas.test.cube");

        JsonObject document = result.document();
        assertEquals("1.12.0", document.get("format_version").getAsString());
        JsonObject model = document.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        assertEquals("geometry.uas.test.cube",
            model.getAsJsonObject("description").get("identifier").getAsString());
        assertEquals(16, model.getAsJsonObject("description").get("texture_width").getAsInt());

        JsonObject cube = model.getAsJsonArray("bones").get(0).getAsJsonObject()
            .getAsJsonArray("cubes").get(0).getAsJsonObject();
        // Java space is 0..16 from the minimum corner; Bedrock is centred on x and z, so a
        // full block is [-8,0,-8] with size 16. This is the assertion that pins the mapping.
        assertEquals(List.of(-8d, 0d, -8d), numbers(cube.getAsJsonArray("origin")));
        assertEquals(List.of(16d, 16d, 16d), numbers(cube.getAsJsonArray("size")));
        assertFalse(result.lossy(), "a plain full cube loses nothing: " + result.notes());
    }

    @Test
    void aShapedCubeKeepsItsProportionsAndOffset() {
        BlockGeometry geometry = BlockGeometry.builder()
            .element(dev.uas.api.block.Cuboid.builder(4, 0, 4, 12, 6, 12).allFaces("#all").build())
            .texture("#all", "uas:block/test")
            .build();

        JsonObject cube = BedrockGeometryCompiler
            .compile(geometry, "geometry.uas.test.pedestal").document()
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
            .getAsJsonArray("bones").get(0).getAsJsonObject()
            .getAsJsonArray("cubes").get(0).getAsJsonObject();

        // x 4..12 -> -4..4 centred; y 0..6 unchanged.
        assertEquals(List.of(-4d, 0d, -4d), numbers(cube.getAsJsonArray("origin")));
        assertEquals(List.of(8d, 6d, 8d), numbers(cube.getAsJsonArray("size")));
    }

    @Test
    void aRotatedElementIsBakedIntoItsBoundsAndReported() {
        BlockGeometry geometry = BlockGeometry.builder()
            .element(dev.uas.api.block.Cuboid.builder(0, 0, 0, 16, 4, 16)
                .allFaces("#all")
                .rotation(Rotation.around(0, 45, 0, dev.uas.api.math.Vec3.of(8, 0, 8)))
                .build())
            .texture("all", "uas:block/test")
            .build();

        BedrockGeometryCompiler.Result result =
            BedrockGeometryCompiler.compile(geometry, "geometry.uas.test.angled");

        assertTrue(result.lossy(), "a rotation cannot be represented without a sign convention");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("rotated")),
            "the loss must be named: " + result.notes());
        // Baked to the rotated corners, so the box is at least as large as the original.
        JsonObject cube = result.document().getAsJsonArray("minecraft:geometry").get(0)
            .getAsJsonObject().getAsJsonArray("bones").get(0).getAsJsonObject()
            .getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertTrue(cube.getAsJsonArray("size").get(0).getAsDouble() >= 16d);
    }

    @Test
    void geometryJsonIsParseableAndStable() {
        BedrockGeometryCompiler.Result first =
            BedrockGeometryCompiler.compile(fullCube(RenderLayer.CUTOUT), "geometry.uas.test.cube");
        BedrockGeometryCompiler.Result second =
            BedrockGeometryCompiler.compile(fullCube(RenderLayer.CUTOUT), "geometry.uas.test.cube");

        assertEquals(first.json(), second.json(), "the same input must produce identical bytes");
        assertNotNull(JsonParser.parseString(first.json()).getAsJsonObject());
    }

    // ------------------------------------------------------------ block definition

    @Test
    void theDefinitionReferencesTheGeometryAndTheRenderMethod() {
        CustomBlock custom = block("lamp", fullCube(RenderLayer.TRANSLUCENT));
        BedrockBlockDefinitionCompiler.Result result =
            BedrockBlockDefinitionCompiler.compile(custom, "geometry.uas.lamp");

        JsonObject components = result.document().getAsJsonObject("minecraft:block")
            .getAsJsonObject("components");
        assertEquals("geometry.uas.lamp", components.get("minecraft:geometry").getAsString());
        assertEquals("blend", components.getAsJsonObject("minecraft:material_instances")
            .getAsJsonObject("*").get("render_method").getAsString());
        assertEquals("uas_lamp", components.getAsJsonObject("minecraft:material_instances")
            .getAsJsonObject("*").get("texture").getAsString());
        // Bedrock identifiers join namespace and name with an underscore, not a colon.
        assertEquals("uas_lamp", result.identifier());
    }

    @Test
    void aDecorationHasNoCollisionBoxRatherThanAnEmptyOne() {
        CustomBlock custom = CustomBlock.builder(NamespacedKey.parse("uas:flower"))
            .displayName("Flower")
            .geometry(fullCube(RenderLayer.CUTOUT))
            .physics(dev.uas.api.block.BlockPhysics.decoration())
            .sounds(BlockSoundGroup.uniform(NamespacedKey.parse("uas:flower_step")))
            .build();

        JsonObject components = BedrockBlockDefinitionCompiler
            .compile(custom, "geometry.uas.flower").document()
            .getAsJsonObject("minecraft:block").getAsJsonObject("components");

        assertEquals(com.google.gson.JsonPrimitive.class,
            components.get("minecraft:collision_box").getClass(),
            "a walk-through block must use the boolean form, not a degenerate box");
        assertFalse(components.get("minecraft:collision_box").getAsBoolean());
    }

    @Test
    void lightEmissionIsOnlyEmittedWhenTheBlockEmitsLight() {
        CustomBlock dark = block("dark", fullCube(RenderLayer.SOLID));
        assertFalse(BedrockBlockDefinitionCompiler.compile(dark, "geometry.uas.dark")
            .document().getAsJsonObject("minecraft:block").getAsJsonObject("components")
            .has("minecraft:light_emission"));

        CustomBlock lit = CustomBlock.builder(NamespacedKey.parse("uas:lit"))
            .displayName("Lit")
            .geometry(fullCube(RenderLayer.SOLID))
            .physics(dev.uas.api.block.BlockPhysics.builder().lightEmission(12).build())
            .sounds(BlockSoundGroup.uniform(NamespacedKey.parse("uas:lit_step")))
            .build();
        assertEquals(12, BedrockBlockDefinitionCompiler.compile(lit, "geometry.uas.lit")
            .document().getAsJsonObject("minecraft:block").getAsJsonObject("components")
            .get("minecraft:light_emission").getAsInt());
    }

    @Test
    void hardnessBecomesSecondsToDestroy() {
        assertEquals(0.05d, BedrockBlockDefinitionCompiler.miningSeconds(0f));
        assertEquals(3.0d, BedrockBlockDefinitionCompiler.miningSeconds(2f), 1e-9);
    }

    // ---------------------------------------------------------------------- sounds

    @Test
    void weightedVariantsExpandSoBedrocksUniformPickMatchesJavasWeightedOne() {
        CustomSound sound = CustomSound.builder(NamespacedKey.parse("uas:lamp_break"))
            .variant(SoundVariant.of(NamespacedKey.parse("uas:block/lamp_break_1"), 3))
            .variant(SoundVariant.of(NamespacedKey.parse("uas:block/lamp_break_2"), 1))
            .category(dev.uas.api.sound.SoundCategory.BLOCKS)
            .build();

        BedrockSoundCompiler.Result result = BedrockSoundCompiler.compile(List.of(sound));

        JsonObject definition = result.document().getAsJsonObject("sound_definitions")
            .getAsJsonObject("uas.lamp_break");
        assertEquals("block", definition.get("category").getAsString());
        assertEquals(4, definition.getAsJsonArray("sounds").size(),
            "weight 3 + weight 1 must expand to 4 entries for the probabilities to match");
        assertEquals(4, result.expandedWeights().get("uas.lamp_break"));
        assertEquals("uas.lamp_break", result.bedrockEventOf(NamespacedKey.parse("uas:lamp_break")));
    }

    @Test
    void soundPathsAreRelativeToThePackSoundsDirectoryWithoutAnExtension() {
        CustomSound sound = CustomSound.single(
            NamespacedKey.parse("uas:lamp_break"), NamespacedKey.parse("uas:block/lamp_break"));
        JsonObject entry = BedrockSoundCompiler.compile(List.of(sound)).document()
            .getAsJsonObject("sound_definitions").getAsJsonObject("uas.lamp_break")
            .getAsJsonArray("sounds").get(0).getAsJsonObject();

        assertEquals("sounds/uas/block/lamp_break", entry.get("name").getAsString());
    }

    @Test
    void aStreamedVariantKeepsItsStreamFlag() {
        CustomSound sound = CustomSound.builder(NamespacedKey.parse("uas:music"))
            .variant(SoundVariant.streamed(NamespacedKey.parse("uas:music/theme")))
            .build();
        BedrockSoundCompiler.Result result = BedrockSoundCompiler.compile(List.of(sound));

        JsonObject entry = result.document().getAsJsonObject("sound_definitions")
            .getAsJsonObject("uas.music").getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertTrue(entry.has("stream"), "a long sound must not be decoded into client memory");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("streamed")));
    }

    @Test
    void aPitchRangeCollapsesToTheMidpointAndSaysSo() {
        CustomSound sound = CustomSound.builder(NamespacedKey.parse("uas:blip"))
            .variant(SoundVariant.of(NamespacedKey.parse("uas:blip_1")).withPitchRange(0.5, 1.5))
            .build();
        BedrockSoundCompiler.Result result = BedrockSoundCompiler.compile(List.of(sound));

        JsonObject entry = result.document().getAsJsonObject("sound_definitions")
            .getAsJsonObject("uas.blip").getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals(1.0d, entry.get("pitch").getAsDouble(), 1e-9);
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("pitch range")),
            "collapsing a range must be reported: " + result.notes());
    }

    @Test
    void twoKeysThatWouldShareABedrockEventAreRejected() {
        // uas:a/b and uas:a.b both map to uas.a.b; that collision must be a failure, not a
        // silently overwritten sound.
        CustomSound first = CustomSound.single(
            NamespacedKey.parse("uas:a/b"), NamespacedKey.parse("uas:a_b"));
        CustomSound second = CustomSound.single(
            NamespacedKey.parse("uas:a.b"), NamespacedKey.parse("uas:a_b_dot"));
        assertThrows(IllegalStateException.class,
            () -> BedrockSoundCompiler.compile(List.of(first, second)));
    }

    // -------------------------------------------------------------------------- ui

    private static UiSchema formSchema() {
        return UiSchema.builder(NamespacedKey.parse("uas:menu"))
            .title("Menu")
            .presentation(UiPresentation.MODAL)
            .initialValue("progress", "50")
            .add(UiElement.Text.of("heading", "Hello"))
            .add(UiElement.Button.of("ok", "OK", UiAction.close()))
            .build();
    }

    @Test
    void aFormIsTitleBodyAndButtons() {
        BedrockUiCompiler.Result result = BedrockUiCompiler.form(formSchema(), Map.of(), false);

        JsonObject form = result.document();
        assertEquals("form", form.get("type").getAsString());
        assertEquals("Menu", form.get("title").getAsString());
        assertEquals("Hello", form.get("content").getAsString());
        assertEquals("OK", form.getAsJsonArray("buttons").get(0).getAsJsonObject()
            .get("text").getAsString());
    }

    @Test
    void aFormCannotDrawAnImageAndSaysSo() {
        UiSchema schema = UiSchema.builder(NamespacedKey.parse("uas:menu"))
            .title("Menu")
            .presentation(UiPresentation.MODAL)
            .add(UiElement.Image.of("logo", NamespacedKey.parse("uas:ui/logo")))
            .build();

        BedrockUiCompiler.Result result = BedrockUiCompiler.form(schema, Map.of(), false);
        assertTrue(result.lossy());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("image")),
            "an undrawable element must be named: " + result.notes());
    }

    @Test
    void aBoundProgressBarBecomesAPercentageTheFormCanShow() {
        UiSchema schema = UiSchema.builder(NamespacedKey.parse("uas:menu"))
            .title("Menu")
            .presentation(UiPresentation.MODAL)
            .initialValue("progress", "3")
            .add(UiElement.ProgressBar.of("bar", "progress", 6))
            .build();

        BedrockUiCompiler.Result result =
            BedrockUiCompiler.form(schema, Map.of("progress", "3"), false);
        assertEquals("50%", result.document().get("content").getAsString(),
            "3 of a 0..6 range is half, and a form can only say so as text");
    }

    @Test
    void customJsonEmitsAUsableLayoutElementTree() {
        UiSchema schema = UiSchema.builder(NamespacedKey.parse("uas:hud"))
            .title("HUD")
            .presentation(UiPresentation.OVERLAY)
            .add(UiElement.Spacer.of("gap", Size.px(8), Size.percent(50)))
            .build();

        BedrockUiCompiler.Result result = BedrockUiCompiler.customUi(schema, Map.of());
        JsonObject root = result.document().getAsJsonObject("root");
        assertEquals("full_screen", root.get("anchors").getAsString());
        JsonObject spacer = root.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals("gap", spacer.get("id").getAsString());
        // Sizes stay as unit:value because resolving AUTO/FILL needs a parent extent.
        assertEquals("percent:50.0", spacer.get("height").getAsString());
    }

    @Test
    void aSlotGridIsNeverDrawnIntoAClientPackBecauseTheServerCouldNotServeIt() {
        UiSchema schema = UiSchema.builder(NamespacedKey.parse("uas:container"))
            .title("Container")
            .presentation(UiPresentation.MODAL)
            .add(UiElement.SlotGrid.of("grid", 3, 9))
            .build();

        BedrockUiCompiler.Result result = BedrockUiCompiler.customUi(schema, Map.of());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("slot grid")),
            "a slot grid must be reported, not drawn: " + result.notes());
        assertEquals(0, result.document().getAsJsonObject("root")
            .getAsJsonArray("elements").size());
    }

    @Test
    void compilingAJavaPathForBedrockIsRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> BedrockUiCompiler.compile(
            formSchema(), UiRenderPath.JAVA_HUD_OVERLAY, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BedrockUiCompiler.compile(
            formSchema(), UiRenderPath.JAVA_INVENTORY_MENU, Map.of()));
    }

    @Test
    void theRenderPathSelectsTheOutputShapeAndNothingElseDoes() {
        // The same schema compiled through the two Bedrock paths must produce a form and a
        // layout respectively: the decision is the planner's, not this compiler's.
        assertEquals("form",
            BedrockUiCompiler.compile(formSchema(), UiRenderPath.BEDROCK_CUMULUS_FORM, Map.of())
                .document().get("type").getAsString());
        assertEquals("panel",
            BedrockUiCompiler.compile(formSchema(), UiRenderPath.BEDROCK_CUSTOM_JSON, Map.of())
                .document().getAsJsonObject("root").get("type").getAsString());
        assertTrue(BedrockUiCompiler.isNativelyPresentable(formSchema()));
    }

    @Test
    void attenuationCurveDoesNotChangeTheEmittedSoundList() {
        // Stated so the absence is deliberate: Bedrock has no per-sound attenuation curve,
        // so the curve is applied by the mixer settings rather than the definition.
        CustomSound plain = CustomSound.single(
            NamespacedKey.parse("uas:s"), NamespacedKey.parse("uas:s_1"));
        CustomSound curved = plain.toBuilder().attenuation(AttenuationCurve.inverseSquare(9)).build();
        assertEquals(
            BedrockSoundCompiler.compile(List.of(plain)).json(),
            BedrockSoundCompiler.compile(List.of(curved)).json());
    }

    private static List<Double> numbers(JsonArray array) {
        List<Double> values = new java.util.ArrayList<>();
        array.forEach(element -> values.add(element.getAsDouble()));
        return values;
    }
}
