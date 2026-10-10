package dev.uas.geyser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.pack.ByteArrayAssetSource;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.ui.UiSchema;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.pack.CompressionMode;
import dev.uas.core.pack.LowMemoryPackCompiler;
import dev.uas.core.pack.PackCompileRequest;
import dev.uas.core.pack.PackCompileResult;
import dev.uas.core.ui.UiRenderPath;
import dev.uas.core.ui.UnifiedUIManager;
import dev.uas.geyser.jit.BedrockBlockDefinitionCompiler;
import dev.uas.geyser.jit.BedrockGeometryCompiler;
import dev.uas.geyser.jit.BedrockUiCompiler;
import dev.uas.geyser.jit.JitBlockConverter;
import dev.uas.geyser.jit.JitSoundConverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the catalog into a Bedrock resource pack, on disk, as a zip.
 *
 * <p>The Bedrock half of the delivery problem is genuinely different from the Java half.
 * A Java client downloads a pack over HTTP from the multiplexer; a Bedrock client does not
 * run on the Minecraft protocol at all, so Geyser has to hand its pack to the client itself.
 * That is why this writer produces an archive for Geyser to serve rather than a URL: the
 * transport differs, and pretending otherwise would mean a pack URL no Bedrock client ever
 * fetches.
 *
 * <p>Three things about the implementation are deliberate:
 *
 * <ul>
 *   <li><strong>It reuses {@link LowMemoryPackCompiler}.</strong> The archive is written by
 *       the same disk-backed, chunked, never-buffer-the-tree compiler the Java side uses.
 *       A Bedrock pack on a constrained instance is the same memory problem, so it gets the
 *       same solution rather than a second, weaker one.</li>
 *   <li><strong>Every file is generated from a compiler, not from a string template.</strong>
 *       The geometry, block definitions, sound definitions and UI documents all come from
 *       the tested {@code jit} package. This class only decides <em>where each document
 *       goes in the pack</em> -- the part that is pack layout rather than conversion.</li>
 *   <li><strong>The render path comes from the shared planner.</strong> Which UI documents
 *       are emitted is decided by {@link UnifiedUIManager}, the same object the Java side
 *       asks. There is exactly one place that decides a schema is form-expressible.</li>
 * </ul>
 */
public final class GeyserAssetWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Bedrock resource pack manifest format version. */
    public static final int MANIFEST_FORMAT_VERSION = 2;

    /** The Bedrock pack manifest UUID, fixed so a rebuild replaces the pack rather than stacking. */
    public static final String PACK_UUID = "5f2a1b34-9c62-4f2b-8d70-3c1a7e5b9d01";

    private final Path outputDirectory;
    private final Path scratchDirectory;
    private final MemoryBudget budget;
    private final UnifiedUIManager uiPlanner;

    public GeyserAssetWriter(
        Path outputDirectory, Path scratchDirectory, MemoryBudget budget, UnifiedUIManager uiPlanner) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
        this.scratchDirectory = Objects.requireNonNull(scratchDirectory, "scratchDirectory");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.uiPlanner = Objects.requireNonNull(uiPlanner, "uiPlanner");
    }

    /** What the write produced, including every conversion note worth showing an operator. */
    public record Written(Path archive, PackCompileResult compile, List<String> notes) {

        public Written {
            Objects.requireNonNull(archive, "archive");
            Objects.requireNonNull(compile, "compile");
            notes = List.copyOf(notes);
        }

        public String describe() {
            return compile.describe() + (notes.isEmpty() ? "" : ", notes=" + notes);
        }
    }

    /** Compiles the whole catalog into one Bedrock pack zip. */
    public Written write(UasCatalog catalog) throws IOException {
        Objects.requireNonNull(catalog, "catalog");
        List<String> notes = new ArrayList<>();
        List<ByteArrayAssetSource> entries = new ArrayList<>();

        JsonObject blocks = new JsonObject();
        JsonObject terrainTextures = new JsonObject();
        JsonArray textureData = new JsonArray();

        for (CustomBlock block : catalog.blocks()) {
            // One conversion entry point per block, so the definition is guaranteed to
            // reference the geometry it was compiled with.
            JitBlockConverter.Conversion conversion =
                JitBlockConverter.convert(block, UasCatalog.geometryIdentifier(block));
            notes.addAll(conversion.notes());

            // Geometry goes in its own file; the block definition is one entry in the
            // single blocks.json, which is how Bedrock's pack layout works.
            entries.add(ByteArrayAssetSource.utf8(
                "models/entity/" + block.key().path() + ".geo.json", conversion.geometryJson()));
            blocks.add(conversion.geometryIdentifier(),
                com.google.gson.JsonParser.parseString(conversion.definitionJson()));

            String textureKey = conversion.terrainTextureKey();
            terrainTextures.addProperty(textureKey, "textures/blocks/" + textureKey);
            // The texture itself must be supplied by the pack's source assets; the writer
            // records the expectation so a missing texture is a listed note, not a pink cube.
            notes.add("texture '" + textureKey + "' must exist at textures/blocks/"
                + textureKey + ".png in the source assets");
        }

        for (CustomSound sound : catalog.sounds()) {
            notes.add("sound '" + sound.key() + "' needs its OGG files in sounds/");
        }

        entries.add(ByteArrayAssetSource.utf8("blocks.json", GSON.toJson(blocks)));
        entries.add(ByteArrayAssetSource.utf8(
            "textures/terrain_texture.json", GSON.toJson(terrainTextureDocument(terrainTextures))));

        if (!catalog.sounds().isEmpty()) {
            JitSoundConverter.Conversion sounds = JitSoundConverter.convert(catalog.sounds());
            notes.addAll(sounds.notes());
            entries.add(ByteArrayAssetSource.utf8(
                "sounds/sound_definitions.json", sounds.json()));
        }

        JsonObject uiNamespace = new JsonObject();
        for (UiSchema schema : catalog.schemas()) {
            UiRenderPath path = uiPlanner.planFor(schema, dev.uas.core.ui.UiTarget.BEDROCK).path();
            if (path == UiRenderPath.BEDROCK_ACTION_BAR) {
                notes.add("schema '" + schema.key()
                    + "' has no Bedrock surface and was not written into the pack");
                continue;
            }
            BedrockUiCompiler.Result ui =
                BedrockUiCompiler.compile(schema, path, schema.initialValues());
            notes.addAll(ui.notes());
            entries.add(ByteArrayAssetSource.utf8(
                "ui/" + schema.key().namespace() + "_" + schema.key().path() + ".json", ui.json()));
            uiNamespace.addProperty(schema.key().toString(),
                schema.key().namespace() + "_" + schema.key().path());
        }

        entries.add(ByteArrayAssetSource.utf8("texts/en_US.lang", languageFile(catalog)));
        entries.add(ByteArrayAssetSource.utf8("manifest.json", GSON.toJson(manifest(catalog))));
        // pack_icon.png is not generated: a placeholder would be an invalid image the
        // client rejects, and an operator-supplied icon is a one-line addition to the
        // source assets. Its absence is recorded so it is not mistaken for an oversight.
        notes.add("pack_icon.png was not generated; supply a 128x128 PNG if the client "
            + "must show a pack icon");
        if (!uiNamespace.isEmpty()) {
            entries.add(ByteArrayAssetSource.utf8(
                "ui/_uas_namespaces.json", GSON.toJson(uiNamespace)));
        }

        Path archive = outputDirectory.resolve("uas-bedrock-pack.zip");
        PackCompileResult compile = new LowMemoryPackCompiler(budget)
            .compile(PackCompileRequest.builder("uas-bedrock", archive, scratchDirectory)
                // The pack is served to the client by Geyser, not re-hashed per request, so
                // deterministic timestamps buy a stable archive and nothing else.
                .mode(CompressionMode.AUTO)
                .entries(new ArrayList<>(entries))
                .build());
        return new Written(archive, compile, notes);
    }

    /** The Bedrock pack manifest, with a fixed UUID so a rebuild replaces the pack. */
    private static JsonObject manifest(UasCatalog catalog) {
        JsonObject header = new JsonObject();
        header.addProperty("name", "UAS Unified Asset Pack");
        header.addProperty("description", "Custom blocks, sounds and UI compiled by the UAS engine");
        header.addProperty("uuid", PACK_UUID);
        JsonArray version = new JsonArray();
        version.add(1);
        version.add(0);
        version.add(0);
        header.add("version", version);
        int[] minEngine = {1, 20, 0};
        JsonArray minEngineVersion = new JsonArray();
        for (int part : minEngine) {
            minEngineVersion.add(part);
        }
        header.add("min_engine_version", minEngineVersion);

        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        // A module UUID must be distinct from the header's and stable across rebuilds.
        module.addProperty("uuid", "b7d43a11-2c8e-4f57-9a3b-6e0f1d2c8a44");
        JsonArray moduleVersion = new JsonArray();
        moduleVersion.add(1);
        moduleVersion.add(0);
        moduleVersion.add(0);
        module.add("version", moduleVersion);
        JsonArray modules = new JsonArray();
        modules.add(module);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", MANIFEST_FORMAT_VERSION);
        manifest.add("header", header);
        manifest.add("modules", modules);
        return manifest;
    }

    private static JsonObject terrainTextureDocument(JsonObject textureData) {
        JsonObject document = new JsonObject();
        document.addProperty("resource_pack_name", "uas");
        document.addProperty("texture_name", "atlas.terrain");
        document.addProperty("padding", 8);
        document.addProperty("num_mip_levels", 4);
        document.add("texture_data", textureData);
        return document;
    }

    /** Pack language entries, so a block's display name is the declared one. */
    private static String languageFile(UasCatalog catalog) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Generated by the UAS engine.\n");
        for (CustomBlock block : catalog.blocks()) {
            builder.append("tile.")
                .append(UasCatalog.geometryIdentifier(block).replace("geometry.uas.", ""))
                .append(".name=")
                .append(block.displayName())
                .append('\n');
        }
        return builder.toString();
    }

    /** The zip path Geyser is handed. */
    public Path archivePath() {
        return outputDirectory.resolve("uas-bedrock-pack.zip");
    }

    /** Every block's geometry and definition, for a conversion report. */
    public Map<String, String> preview(UasCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        Map<String, String> preview = new LinkedHashMap<>();
        for (CustomBlock block : catalog.blocks()) {
            String identifier = UasCatalog.geometryIdentifier(block);
            preview.put(block.key().toString(), JitBlockConverter.convert(block, identifier).describe());
        }
        return preview;
    }

    /** Charset for generated text assets; stated so the pack is byte-identical across JVMs. */
    static final java.nio.charset.Charset ASSET_CHARSET = StandardCharsets.UTF_8;
}
