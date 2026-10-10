package dev.uas.geyser.jit;

import dev.uas.api.NamespacedKey;
import dev.uas.api.sound.CustomSound;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry point for "turn these Java custom sounds into Bedrock sound definitions".
 *
 * <p>Two facts make this more than a pass-through to {@link BedrockSoundCompiler}, and both
 * are things a caller gets wrong when left to assemble them by hand:
 *
 * <ul>
 *   <li><strong>The event mapping is the deliverable, not the JSON.</strong> Bedrock does not
 *       reference a sound by its Java key, so anything that wants to <em>play</em> a converted
 *       sound needs the Java-key-to-Bedrock-event mapping. Producing the document without the
 *       mapping yields a pack nobody can trigger.</li>
 *   <li><strong>The expansion cost has to be visible.</strong> Java's weighted variants become
 *       a flat, uniformly-picked list, so a sound with weight 40 becomes 40 entries. That is
 *       correct but it is also payload the operator is paying for, and it is the kind of number
 *       that only surfaces in a report.</li>
 * </ul>
 */
public final class JitSoundConverter {

    private JitSoundConverter() {
    }

    /** The converted document plus everything needed to use and audit it. */
    public record Conversion(
        String json,
        Map<NamespacedKey, String> eventMapping,
        int eventCount,
        int expandedEntries,
        List<String> notes) {

        public Conversion {
            Objects.requireNonNull(json, "json");
            eventMapping = Map.copyOf(eventMapping);
            notes = List.copyOf(notes);
        }

        /** The Bedrock event id a Java sound key plays as, or empty when it was not converted. */
        public Optional<String> eventFor(NamespacedKey key) {
            return Optional.ofNullable(eventMapping.get(key));
        }

        public boolean lossy() {
            return !notes.isEmpty();
        }

        /**
         * Entries added purely to reproduce Java's weighting: one per unit of weight beyond the
         * first for every variant. Reported so a pack's growth is attributable.
         */
        public int weightExpansion() {
            return Math.max(0, expandedEntries - eventCount);
        }

        public String describe() {
            return eventCount + " event(s), " + expandedEntries + " Bedrock entr(ies)"
                + (notes.isEmpty() ? "" : ", " + notes.size() + " note(s)");
        }
    }

    /** Converts a sound set. */
    public static Conversion convert(Collection<CustomSound> sounds) {
        Objects.requireNonNull(sounds, "sounds");
        BedrockSoundCompiler.Result compiled = BedrockSoundCompiler.compile(sounds);

        int expanded = 0;
        for (Integer count : compiled.expandedWeights().values()) {
            expanded += count;
        }
        return new Conversion(
            compiled.json(),
            compiled.eventMapping(),
            compiled.eventMapping().size(),
            expanded,
            compiled.notes());
    }

    /** The Bedrock event id for a Java sound key, without compiling anything. */
    public static String eventFor(NamespacedKey key) {
        return BedrockSoundCompiler.bedrockEvent(Objects.requireNonNull(key, "key"));
    }
}
