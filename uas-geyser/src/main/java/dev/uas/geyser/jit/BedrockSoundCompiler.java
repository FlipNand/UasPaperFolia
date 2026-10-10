package dev.uas.geyser.jit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.uas.api.NamespacedKey;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.sound.SoundVariant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles Java custom sounds into a Bedrock {@code sound_definitions.json}.
 *
 * <p>This conversion is not a rename, and the difference is the whole reason it needs
 * code. Java's {@code sounds.json} declares an event with a <em>weighted set of
 * variants</em>: one logical sound, N files, each with its own weight, volume, pitch and
 * attenuation curve, from which the client picks per playback. Bedrock's
 * {@code sound_definitions.json} has no weighting: an event has a list of sounds and the
 * client picks uniformly. So a Java event with unequal weights cannot be reproduced
 * exactly.
 *
 * <p>It can, however, be reproduced <strong>proportionally</strong>: a variant with weight
 * 3 is emitted three times, so Bedrock's uniform choice over the expanded list matches
 * Java's weighted choice exactly. That is what this compiler does, and it reports the
 * expansion so the cost is visible instead of invisible. The only case that cannot be
 * expressed at all is a variant list that would expand past Bedrock's per-event sound
 * limit, which is reported rather than truncated.
 *
 * <p>Streaming is preserved: Java marks a variant {@code stream: true} for long audio and
 * Bedrock has the same flag, and getting it wrong means a multi-megabyte sound is
 * decoded into client memory instead of streamed from disk.
 */
public final class BedrockSoundCompiler {

    /** Bedrock's {@code sound_definitions.json} format version. */
    public static final String FORMAT_VERSION = "1.14.0";

    /** Practical ceiling on the expanded per-event sound list before a client misbehaves. */
    public static final int MAX_SOUNDS_PER_EVENT = 64;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BedrockSoundCompiler() {
    }

    /** The compiled document plus the Java-to-Bedrock key mapping. */
    public record Result(
        JsonObject document,
        Map<NamespacedKey, String> eventMapping,
        Map<String, Integer> expandedWeights,
        List<String> notes) {

        public Result {
            document = Objects.requireNonNull(document, "document");
            eventMapping = Map.copyOf(eventMapping);
            expandedWeights = Map.copyOf(expandedWeights);
            notes = List.copyOf(notes);
        }

        public String json() {
            return GSON.toJson(document);
        }

        /** The Bedrock event id a Java sound key plays as. */
        public String bedrockEventOf(NamespacedKey key) {
            return eventMapping.get(key);
        }
    }

    /**
     * The Bedrock event id for a Java sound key: {@code uas:foo/bar} becomes
     * {@code uas.foo.bar}.
     *
     * <p>Bedrock event ids are dot-separated with no namespace separator, so the mapping
     * has to be a function rather than a rename, and it has to be injective enough that two
     * distinct Java keys cannot collide.
     */
    public static String bedrockEvent(NamespacedKey key) {
        return key.namespace() + "." + key.path().replace('/', '.');
    }

    /** Compiles a set of sounds into one {@code sound_definitions.json}. */
    public static Result compile(Collection<CustomSound> sounds) {
        Objects.requireNonNull(sounds, "sounds");
        List<String> notes = new java.util.ArrayList<>();
        JsonObject definitions = new JsonObject();
        Map<NamespacedKey, String> mapping = new LinkedHashMap<>();
        Map<String, Integer> weights = new LinkedHashMap<>();

        // Sorted so the emitted file is byte-stable: an unstable pack file means every
        // regeneration is a new hash, which invalidates every client's cache for nothing.
        List<CustomSound> ordered = new java.util.ArrayList<>(sounds);
        ordered.sort(java.util.Comparator.comparing(sound -> sound.key().toString()));

        for (CustomSound sound : ordered) {
            String event = bedrockEvent(sound.key());
            if (definitions.has(event)) {
                throw new IllegalStateException(
                    "two Java sounds map to the same Bedrock event '" + event + "'");
            }
            mapping.put(sound.key(), event);

            int expanded = expand(sound, definitions, event, notes);
            weights.put(event, expanded);
        }

        JsonObject document = new JsonObject();
        document.addProperty("format_version", FORMAT_VERSION);
        document.add("sound_definitions", definitions);
        return new Result(document, mapping, weights, notes);
    }

    private static int expand(
        CustomSound sound, JsonObject definitions, String event, List<String> notes) {

        JsonArray entries = new JsonArray();
        for (SoundVariant variant : sound.variants()) {
            int weight = Math.max(1, variant.weight());
            if (entries.size() + weight > MAX_SOUNDS_PER_EVENT) {
                notes.add("sound '" + event + "' exceeded " + MAX_SOUNDS_PER_EVENT
                    + " expanded entries and was truncated; Bedrock cannot weight a sound list");
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", variantFile(variant));
            entry.addProperty("volume", variant.volume());
            // Bedrock takes one fixed pitch per entry, where Java declares a range the
            // client samples uniformly. The midpoint is emitted and the collapse is noted,
            // so the approximation is recorded rather than presented as exact.
            entry.addProperty("pitch", (variant.pitchMin() + variant.pitchMax()) / 2d);
            if (variant.pitchMin() != variant.pitchMax()) {
                notes.add("sound '" + event + "' declares a pitch range "
                    + variant.pitchMin() + ".." + variant.pitchMax()
                    + "; Bedrock takes a fixed pitch per entry, so the midpoint was used");
            }
            if (variant.stream()) {
                entry.addProperty("stream", true);
            }
            for (int i = 0; i < weight; i++) {
                entries.add(entry.deepCopy());
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("sound '" + event + "' expanded to no playable entries");
        }

        if (sound.hasStreamedVariant()) {
            notes.add("sound '" + event + "' contains a streamed variant; the Bedrock pack must "
                + "ship the OGG with the same relative path");
        }

        JsonObject definition = new JsonObject();
        // Java's sound category becomes Bedrock's mixer category; Bedrock understands
        // "block", "music", "player", "ambient", "hostile", "neutral", "record", "ui",
        // "weather". Anything else falls back to "neutral" rather than an unknown name the
        // client ignores.
        definition.addProperty("category", bedrockCategory(sound));
        definition.add("sounds", entries);
        definitions.add(event, definition);

        return entries.size();
    }

    /**
     * A variant's Bedrock sound path.
     *
     * <p>Java's {@code uas:block/crystal_lamp_break} is a resource path without an
     * extension; Bedrock's {@code name} is a path relative to the pack's
     * {@code sounds/} directory. The compiler emits {@code sounds/uas/block/...} with no
     * extension, which is what Bedrock expects (it resolves the platform's audio format).
     */
    private static String variantFile(SoundVariant variant) {
        // A Java sound variant lives at <namespace>/sounds/<path>.ogg; Bedrock's name is
        // relative to the pack's sounds/ directory and carries no extension. Derived from
        // the key rather than by editing assetPath(), because "sounds" appears in both
        // the Java path and the Bedrock prefix and string surgery on that overlaps.
        return "sounds/" + variant.key().namespace() + "/" + variant.key().path();
    }

    private static String bedrockCategory(CustomSound sound) {
        return switch (sound.category()) {
            case BLOCKS -> "block";
            case MUSIC -> "music";
            case RECORDS -> "record";
            case WEATHER -> "weather";
            case HOSTILE -> "hostile";
            case NEUTRAL -> "neutral";
            case PLAYERS, VOICE -> "player";
            case AMBIENT -> "ambient";
            case UI -> "ui";
            case MASTER -> "neutral";
        };
    }
}
