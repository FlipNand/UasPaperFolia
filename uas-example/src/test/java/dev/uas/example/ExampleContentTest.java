package dev.uas.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.uas.api.block.BlockGeometry;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.block.RenderLayer;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.ui.UiPresentation;
import dev.uas.api.ui.UiSchema;
import dev.uas.core.block.NoteBlockStateMapper;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import dev.uas.core.pack.LowMemoryPackCompiler;
import dev.uas.core.pack.PackCompileRequest;
import dev.uas.core.pack.PackCompileResult;
import dev.uas.core.ui.UiRenderPath;
import dev.uas.core.ui.UiTarget;
import dev.uas.core.ui.UnifiedUIManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The example is the only place where the whole chain runs end to end -- declaration,
 * state allocation, pack generation, compilation to a real archive -- so it is tested as a
 * chain rather than as separate units. A pack that compiles but binds the wrong note-block
 * state is not a working feature, and nothing short of reading the finished archive catches
 * that.
 */
class ExampleContentTest {

    private static final CustomBlock LAMP = ExampleContent.crystalLamp();
    private static final List<CustomSound> SOUNDS = ExampleContent.lampSoundEvents();

    // ---------------------------------------------------------------- declarations

    @Test
    void theLampIsShapedAnimatedTranslucentAndEmissive() {
        assertEquals(2, LAMP.geometry().elements().size(),
            "a base and a crystal body: only a shaped block exercises geometry conversion");
        assertFalse(LAMP.geometry().isUnitCube());
        assertEquals(RenderLayer.TRANSLUCENT, LAMP.geometry().renderLayer(),
            "the render layer must survive both conversions or the crystal renders opaque");
        assertEquals(1, LAMP.geometry().animatedTextures().size());
        assertEquals(12, LAMP.physics().lightEmission(),
            "light emission is what makes the note-block backend's limitation visible");
        assertTrue(LAMP.physics().hasCollision());
        assertEquals(5, LAMP.sounds().eventKeys().size(), "five distinct sound events");
        assertFalse(LAMP.sounds().eventKeys().stream().distinct().count() < 5,
            "the sound group must not collapse to one key");
    }

    @Test
    void everyTextureSlotTheModelReferencesIsDeclared() {
        // resolveTexture throws on an undeclared slot, which is the loud failure the API
        // intends; asserting it here means a model edit cannot silently break the pack.
        List<dev.uas.api.NamespacedKey> referenced = LAMP.geometry().referencedTextures();
        assertFalse(referenced.isEmpty());
        for (dev.uas.api.NamespacedKey texture : referenced) {
            assertNotNull(texture.namespace());
        }
    }

    @Test
    void theBreakSoundIsWeightedSoTheBedrockExpansionIsExercised() {
        CustomSound breakSound = SOUNDS.stream()
            .filter(sound -> sound.key().path().endsWith("_break"))
            .findFirst().orElseThrow();
        assertEquals(2, breakSound.variants().size());
        assertEquals(4, breakSound.totalWeight(),
            "unequal weights are the one thing Bedrock cannot express, so the example must have them");
    }

    @Test
    void theHudBindsOnlyKeysItDeclares() {
        UiSchema hud = ExampleContent.chargeHud();
        assertEquals(UiPresentation.OVERLAY, hud.presentation());
        assertTrue(hud.boundStateKeys().contains(ExampleContent.UI_STATE_KEY));
        assertTrue(hud.undeclaredStateKeys().isEmpty(),
            "a bound key with no initial value is a runtime failure: " + hud.undeclaredStateKeys());
        assertFalse(hud.isFullyFormExpressible(),
            "the progress bar is what forces Bedrock down the custom-UI-JSON path");
    }

    @Test
    void thePlannerSendsTheJavaClientAnOverlayAndTheBedrockClientCustomJson() {
        UnifiedUIManager manager = new UnifiedUIManager();
        UiSchema hud = ExampleContent.chargeHud();
        manager.register(hud);

        assertEquals(UiRenderPath.JAVA_HUD_OVERLAY, manager.planFor(hud, UiTarget.JAVA).path());
        // The author refused the modal fallback, so Bedrock gets injected JSON rather than a
        // dialog: this is the cross-platform decision the example exists to demonstrate.
        assertEquals(UiRenderPath.BEDROCK_CUSTOM_JSON, manager.planFor(hud, UiTarget.BEDROCK).path());
    }

    // -------------------------------------------------------------------- the pack

    @Test
    void theStateUsedByThePackIsTheStateThePlacerWouldSet() {
        // One allocator, two consumers. If the generator and the placer disagreed, every lamp
        // would render as a default note block and nothing server-side would say why.
        var allocated = NoteBlockStateMapper.allocate(List.of(LAMP.key()));
        var expected = allocated.get(LAMP.key());
        assertNotNull(expected);
        assertEquals(expected, NoteBlockStateMapper.stateOf(LAMP.key()).orElseThrow());
    }

    @Test
    void theCompiledPackBindsTheNoteBlockStateToTheCustomModel(@TempDir Path dir) throws IOException {
        PackCompileRequest request = PackCompileRequest.builder(
                "uas-example", dir.resolve("pack.zip"), dir.resolve("scratch"))
            .entries(new java.util.ArrayList<dev.uas.api.pack.AssetSource>(
                ExamplePackBuilder.entries(LAMP, SOUNDS)))
            .build();

        PackCompileResult result = new LowMemoryPackCompiler(
            MemoryBudget.forProfile(MemoryProfile.LOW_RAM, Runtime.getRuntime().maxMemory()))
            .compile(request);

        assertTrue(result.outputBytes() > 0);
        assertEquals(40, result.sha1().length());

        try (ZipFile zip = new ZipFile(result.output().toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = entryNames(zip);
            assertTrue(names.contains("pack.mcmeta"), names.toString());
            assertTrue(names.contains("assets/minecraft/blockstates/note_block.json"),
                "the vanilla blockstate override must be in the vanilla namespace: " + names);

            JsonObject blockstates = JsonParser.parseString(
                readEntry(zip, "assets/minecraft/blockstates/note_block.json")).getAsJsonObject();
            JsonObject variants = blockstates.getAsJsonObject("variants");
            String expectedState = NoteBlockStateMapper.stateOf(LAMP.key()).orElseThrow()
                .blockStateString();
            assertTrue(variants.has(expectedState),
                "the pack must declare the exact state the placer sets; expected '"
                    + expectedState + "' but the pack declares " + variants.keySet());
            assertEquals("uas:block/crystal_lamp",
                variants.getAsJsonObject(expectedState).get("model").getAsString());
        }
    }

    @Test
    void theCompiledPackShipsTheModelSoundDefinitionsAndAnimationSidecar(@TempDir Path dir)
        throws IOException {
        PackCompileRequest request = PackCompileRequest.builder(
                "uas-example", dir.resolve("pack.zip"), dir.resolve("scratch"))
            .entries(new java.util.ArrayList<dev.uas.api.pack.AssetSource>(
                ExamplePackBuilder.entries(LAMP, SOUNDS)))
            .build();
        PackCompileResult result = new LowMemoryPackCompiler(
            MemoryBudget.forProfile(MemoryProfile.LOW_RAM, Runtime.getRuntime().maxMemory()))
            .compile(request);

        try (ZipFile zip = new ZipFile(result.output().toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = entryNames(zip);

            String modelPath = "assets/uas/models/block/crystal_lamp.json";
            assertTrue(names.contains(modelPath), names.toString());
            JsonObject model = JsonParser.parseString(readEntry(zip, modelPath)).getAsJsonObject();
            assertEquals(2, model.getAsJsonArray("elements").size(),
                "the model must carry the block's own shape, not a substituted cube");

            assertTrue(names.contains("assets/uas/sounds.json"));
            JsonObject sounds = JsonParser.parseString(
                readEntry(zip, "assets/uas/sounds.json")).getAsJsonObject();
            assertTrue(sounds.has("uas:block/crystal_lamp_break"), sounds.keySet().toString());
            // Two entries *with weights*, not four flat ones. That difference is the whole
            // reason the Bedrock compiler exists: Java carries the weighting, Bedrock has no
            // way to express it, so the Bedrock side expands to four equally weighted entries
            // to reproduce the same probabilities. Asserting 2 here pins the Java contract.
            var breakVariants = sounds.getAsJsonObject("uas:block/crystal_lamp_break")
                .getAsJsonArray("sounds");
            assertEquals(2, breakVariants.size());
            assertEquals(3, breakVariants.get(0).getAsJsonObject().get("weight").getAsInt());
            assertEquals(1, breakVariants.get(1).getAsJsonObject().get("weight").getAsInt());

            // The animation is only real if the sidecar exists: a declared animation with no
            // .mcmeta is a static texture, which is the classic silently-wrong pack.
            assertTrue(names.contains(
                    "assets/uas/textures/block/crystal_lamp_crystal.png.mcmeta"),
                "every declared animation must ship its .mcmeta sidecar: " + names);
        }
    }

    @Test
    void thePackIsByteStableAcrossCompiles(@TempDir Path dir) throws IOException {
        // A stable archive means an unchanged declaration does not invalidate every client's
        // immutable cache, which is exactly what the pack URL relies on.
        MemoryBudget budget =
            MemoryBudget.forProfile(MemoryProfile.LOW_RAM, Runtime.getRuntime().maxMemory());
        PackCompileResult first = compile(dir.resolve("a"), budget);
        PackCompileResult second = compile(dir.resolve("b"), budget);
        assertEquals(first.sha1(), second.sha1(),
            "identical declarations must produce an identical archive");
    }

    @Test
    void thePackStaysWithinTheConstrainedBudget(@TempDir Path dir) throws IOException {
        // The example is small, but the assertion is the important one: compiling it on the
        // low-RAM profile must not need anything like a whole-heap buffer, even though the
        // entries are generated in memory. A regression that buffered the tree would show up
        // here as a peak fraction near 1.
        PackCompileResult result = compile(dir.resolve("p"), MemoryBudget.forProfile(
            MemoryProfile.LOW_RAM, Runtime.getRuntime().maxMemory()));
        assertTrue(result.peakFractionOfMaxHeap() < 0.5d,
            "compiling the example used " + result.describe());
    }

    private static PackCompileResult compile(Path dir, MemoryBudget budget) throws IOException {
        java.nio.file.Files.createDirectories(dir);
        PackCompileRequest request = PackCompileRequest.builder(
                "uas-example", dir.resolve("pack.zip"), dir.resolve("scratch"))
            .entries(new java.util.ArrayList<dev.uas.api.pack.AssetSource>(
                ExamplePackBuilder.entries(LAMP, SOUNDS)))
            .deterministicTimestamps(true)
            .build();
        return new LowMemoryPackCompiler(budget).compile(request);
    }

    private static Set<String> entryNames(ZipFile zip) {
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            names.add(entries.nextElement().getName());
        }
        return names;
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        try (var stream = zip.getInputStream(zip.getEntry(name))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------- determinism

    /**
     * The companion to {@link #thePackIsByteStableAcrossCompiles}: that test compiles twice in
     * one JVM and so cannot see a difference caused by state that is fixed per process. The
     * immutable map factories spread their entries using a per-JVM salt, so a slot map that
     * goes through one iterates differently on every run -- identical servers, different
     * archive, and therefore different pack identities forever.
     *
     * <p>Asserting declaration order is the only in-process way to pin that down, which is why
     * the assertion is on the order and not on the bytes.
     */
    @Test
    void aGeometryKeepsItsTextureSlotsInDeclarationOrder() {
        // Enough slots that a hash-ordered map would essentially never match, so this fails
        // loudly rather than occasionally if the slots are ever re-normalised.
        Map<String, String> declared = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            declared.put("slot" + i, "uas:block/tex" + i);
        }

        BlockGeometry geometry = BlockGeometry.unitCube(declared, RenderLayer.SOLID);

        assertEquals(List.copyOf(declared.keySet()), List.copyOf(geometry.textures().keySet()),
            "iteration order is part of the API's contract, not an accident of the map factory");
        assertEquals(List.copyOf(declared.values()), List.copyOf(geometry.textures().values()));
    }

    @Test
    void theModelParticleTextureComesFromTheUpFaceRatherThanAnArbitrarySlot() {
        // The bug this pins: a particle slot chosen as "the first entry of the texture map"
        // made break particles depend on map iteration order, so the crystal lamp sometimes
        // shattered into base-texture particles and sometimes into crystal ones.
        JsonObject model = JsonParser.parseString(
                ExamplePackBuilder.blockModel(LAMP).toString()).getAsJsonObject();

        assertEquals("uas:block/crystal_lamp_crystal",
            model.getAsJsonObject("textures").get("particle").getAsString(),
            "particles come from the topmost element's up face, which is the crystal body");
        assertEquals("block/block", model.get("parent").getAsString());
    }

    /**
     * Every face must reference a texture <em>slot</em>, and a slot the model declares.
     *
     * <p>Without the leading {@code #} a face's texture is read as a concrete path, so a face
     * written as {@code "base"} becomes {@code minecraft:base} and the block renders as the
     * missing-texture pattern -- while the server-side model, the compile and every other check
     * here still pass. This is the one defect in the chain that only the finished JSON can show.
     */
    @Test
    void everyFaceReferenceNamesADeclaredTextureSlot() {
        JsonObject model = JsonParser.parseString(
                ExamplePackBuilder.blockModel(LAMP).toString()).getAsJsonObject();
        Set<String> slots = model.getAsJsonObject("textures").keySet();

        assertFalse(model.getAsJsonArray("elements").isEmpty());
        for (var element : model.getAsJsonArray("elements")) {
            for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                String reference = face.getValue().getAsJsonObject().get("texture").getAsString();
                String where = face.getKey() + " face references " + reference;
                assertTrue(reference.startsWith("#"),
                    where + ", which the client reads as a literal texture path, not a slot");
                assertTrue(slots.contains(reference.substring(1)),
                    where + " but the model declares only " + slots);
            }
        }
    }
}
