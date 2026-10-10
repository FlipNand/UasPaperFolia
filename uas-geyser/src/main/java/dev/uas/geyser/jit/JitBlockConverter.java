package dev.uas.geyser.jit;

import dev.uas.api.block.CustomBlock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * One entry point for "turn this Java custom block into Bedrock artefacts".
 *
 * <p>The work is done by two compilers with different jobs --
 * {@link BedrockGeometryCompiler} produces the model, {@link BedrockBlockDefinitionCompiler}
 * produces the block's components -- and any caller that registers a Bedrock block needs
 * <em>both</em>, in the right relationship: the definition must reference the geometry's
 * identifier, and a mismatch there is a block that exists with no model.</p>
 *
 * <p>So this class exists to make that pairing impossible to get wrong, and to produce a
 * single conversion report. The report is not decoration: the compilers between them can lose
 * an element rotation, approximate a multi-box collider, and name textures the pack must
 * supply, and an operator registering a hundred blocks needs one place to read all of it
 * rather than a hundred log lines from two sources.
 *
 * <p>Callers who only want one artefact can still use the compilers directly; this is the
 * whole-block path, not a wrapper around every method.
 */
public final class JitBlockConverter {

    private JitBlockConverter() {
    }

    /**
     * Everything Bedrock needs for one block.
     *
     * @param identifier the Bedrock block identifier ({@code namespace_name})
     * @param geometryIdentifier the identifier the definition references
     * @param terrainTextureKey the short name a {@code terrain_texture.json} entry must define
     */
    public record Conversion(
        String identifier,
        String geometryIdentifier,
        String geometryJson,
        String definitionJson,
        String terrainTextureKey,
        boolean lossy,
        List<String> notes) {

        public Conversion {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(geometryIdentifier, "geometryIdentifier");
            Objects.requireNonNull(geometryJson, "geometryJson");
            Objects.requireNonNull(definitionJson, "definitionJson");
            Objects.requireNonNull(terrainTextureKey, "terrainTextureKey");
            notes = List.copyOf(notes);
        }

        /** The file name the geometry document should be written as. */
        public String geometryFileName() {
            return identifier.replace(":", "_") + ".geo.json";
        }

        /** One line per block for a conversion report. */
        public String describe() {
            return identifier + " -> " + geometryIdentifier
                + (notes.isEmpty() ? " (lossless)" : " (" + notes.size() + " note(s))");
        }
    }

    /** Converts one block using its derived geometry identifier. */
    public static Conversion convert(CustomBlock block) {
        Objects.requireNonNull(block, "block");
        return convert(block, "geometry.uas." + block.key().namespace() + "." + block.key().path());
    }

    /**
     * Converts one block with an explicit geometry identifier.
     *
     * <p>The identifier is a parameter rather than always derived because a Bedrock pack may
     * deliberately share one geometry between several blocks, and re-deriving it per block
     * would silently break that.
     */
    public static Conversion convert(CustomBlock block, String geometryIdentifier) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(geometryIdentifier, "geometryIdentifier");

        BedrockGeometryCompiler.Result geometry =
            BedrockGeometryCompiler.compile(block.geometry(), geometryIdentifier);
        BedrockBlockDefinitionCompiler.Result definition =
            BedrockBlockDefinitionCompiler.compile(block, geometryIdentifier);

        List<String> notes = new ArrayList<>(geometry.notes());
        notes.addAll(definition.notes());

        return new Conversion(
            definition.identifier(),
            geometryIdentifier,
            geometry.json(),
            definition.json(),
            BedrockBlockDefinitionCompiler.terrainTextureKey(block),
            geometry.lossy() || !notes.isEmpty(),
            notes);
    }

    /**
     * Converts a whole block set.
     *
     * <p>Notes are aggregated across the set so one report covers the pack, and the geometry
     * identifiers stay per-block because the pack must define each one.
     */
    public static Result convertAll(Collection<CustomBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        List<Conversion> conversions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (CustomBlock block : blocks) {
            Conversion conversion = convert(block);
            conversions.add(conversion);
            notes.addAll(conversion.notes());
        }
        return new Result(conversions, notes);
    }

    /** A whole block set's conversions plus the aggregate report. */
    public record Result(List<Conversion> conversions, List<String> notes) {

        public Result {
            conversions = List.copyOf(conversions);
            notes = List.copyOf(notes);
        }

        public int size() {
            return conversions.size();
        }

        public boolean lossy() {
            return !notes.isEmpty();
        }

        /** Blocks that could not be rendered exactly as declared. */
        public List<String> lossyIdentifiers() {
            List<String> identifiers = new ArrayList<>();
            for (Conversion conversion : conversions) {
                if (conversion.lossy()) {
                    identifiers.add(conversion.identifier());
                }
            }
            return List.copyOf(identifiers);
        }
    }
}
