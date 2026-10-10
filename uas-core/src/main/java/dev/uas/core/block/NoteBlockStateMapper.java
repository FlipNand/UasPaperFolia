package dev.uas.core.block;

import dev.uas.api.NamespacedKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a custom block to a distinct vanilla note-block state.
 *
 * <p>Minecraft has no custom blocks, so the classic technique is to find a vanilla block
 * whose <em>state space</em> is large and unused, and give each custom block its own
 * state. A note block is ideal: instrument (16 values) x note (25 values) x powered
 * (2 values) is 800 distinct, mutually exclusive block states, and a resource pack can
 * give each one its own model. No entities, no client mod, no per-block overhead.
 *
 * <p>Two properties matter and both are the reason this is a separate, dependency-free
 * class rather than logic inside the Paper module:
 *
 * <ol>
 *   <li><strong>The assignment must be collision-free.</strong> Two custom blocks sharing
 *       a note-block state means one of them silently renders as the other. The naive
 *       "hash the key and take modulo capacity" does collide, so this uses a stable
 *       FNV-1a hash followed by linear probing over the free slots. It is a real
 *       allocation, not a guess.</li>
 *   <li><strong>The assignment must be reproducible.</strong> The Paper side places a
 *       block in this state; the resource-pack generator must emit a
 *       {@code blockstates/note_block.json} variant for the <em>same</em> state. If the
 *       two disagree, every block renders as a default note block. So the mapping is a
 *       pure function of the key set -- sorted, stable, and identical on both sides --
 *       and it lives where both can call it.</li>
 * </ol>
 *
 * <p>{@link #allocate} is the collision-free form and is what production uses. The
 * single-key {@link #hashSlot} is kept because it is what a generator needs when it has
 * to reason about one block at a time, and because it is the documented starting point
 * for the probe, which makes the algorithm inspectable.
 */
public final class NoteBlockStateMapper {

    /** Note-block instruments in the vanilla state space. */
    public static final int INSTRUMENTS = 16;

    /** Note-block notes (0..24) in the vanilla state space. */
    public static final int NOTES = 25;

    /** Powered / unpowered. */
    public static final int POWERED_STATES = 2;

    /**
     * The vanilla {@code note_block} {@code instrument} values, in their ids' order.
     *
     * <p>Written out rather than derived from Bukkit's {@code Instrument} enum, because
     * the two do not agree: Bukkit names the same value {@code PIANO} where the block
     * state is {@code harp}, among others, and Bukkit has since added extra constants
     * that are not part of this state space at all. A pack's variant key must use the
     * <em>block-state</em> name, so that is the canonical spelling here.
     */
    public static final List<String> VANILLA_INSTRUMENTS = List.of(
        "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
        "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit",
        "banjo", "pling");

    /** Distinct note-block states: 16 x 25 x 2. */
    public static final int CAPACITY = INSTRUMENTS * NOTES * POWERED_STATES;

    /** FNV-1a 32-bit offset basis. */
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;

    /** FNV-1a 32-bit prime. */
    private static final int FNV_PRIME = 0x01000193;

    private NoteBlockStateMapper() {
    }

    /** One note-block state. */
    public record State(int instrument, int note, boolean powered) {

        public State {
            if (instrument < 0 || instrument >= INSTRUMENTS) {
                throw new IllegalArgumentException("instrument must be 0..15, got " + instrument);
            }
            if (note < 0 || note >= NOTES) {
                throw new IllegalArgumentException("note must be 0..24, got " + note);
            }
        }

        /** The vanilla block-state instrument name for this state. */
        public String instrumentName() {
            return VANILLA_INSTRUMENTS.get(instrument);
        }

        /** The flat index this state occupies in the matrix. */
        public int index() {
            return (instrument * NOTES + note) * POWERED_STATES + (powered ? 1 : 0);
        }

        public static State ofIndex(int index) {
            if (index < 0 || index >= CAPACITY) {
                throw new IllegalArgumentException("state index must be 0.." + (CAPACITY - 1) + ", got " + index);
            }
            int powered = index % POWERED_STATES;
            int rest = index / POWERED_STATES;
            return new State(rest / NOTES, rest % NOTES, powered == 1);
        }

        /**
         * The vanilla block-state string, which is also what a pack's variant key uses.
         *
         * <p>Named, not numeric: {@code instrument=harp}, because that is the spelling
         * {@code blockstates/note_block.json} keys variants by.
         */
        public String blockStateString() {
            return "instrument=" + instrumentName() + ",note=" + note + ",powered=" + powered;
        }
    }

    /**
     * The probe's starting slot for a key: a stable FNV-1a hash reduced into the matrix.
     *
     * <p>FNV-1a rather than {@code String.hashCode()} because the latter is not specified
     * across JVMs and would let the Paper side and a generator disagree about a layout.
     */
    public static int hashSlot(NamespacedKey key) {
        return hashSlot(key.toString());
    }

    public static int hashSlot(String key) {
        Objects.requireNonNull(key, "key");
        int hash = FNV_OFFSET_BASIS;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        // Math.floorMod, not %, so a negative hash cannot produce a negative index.
        return Math.floorMod(hash, CAPACITY);
    }

    /** The slot a single key would start probing from, as a concrete state. */
    public static State hashState(NamespacedKey key) {
        return State.ofIndex(hashSlot(key));
    }

    /**
     * Assigns every key its own state, resolving collisions by linear probing.
     *
     * <p>Keys are processed in sorted order, so the result depends only on the <em>set</em>
     * of keys and not on the order they were registered. That is what makes the mapping
     * reproducible: a generator that sorts the same keys gets the same answer.
     *
     * @throws IllegalStateException if the keys outnumber the available states
     */
    public static Map<NamespacedKey, State> allocate(Collection<NamespacedKey> keys) {
        Objects.requireNonNull(keys, "keys");
        List<NamespacedKey> ordered = new ArrayList<>(keys);
        ordered.sort(java.util.Comparator.comparing(NamespacedKey::toString));
        if (ordered.size() > CAPACITY) {
            throw new IllegalStateException(
                "cannot host " + ordered.size() + " note-block-backed blocks; the state matrix holds "
                    + CAPACITY);
        }

        boolean[] taken = new boolean[CAPACITY];
        Map<NamespacedKey, State> assigned = new LinkedHashMap<>();
        for (NamespacedKey key : ordered) {
            int slot = hashSlot(key);
            while (taken[slot]) {
                slot = (slot + 1) % CAPACITY;
            }
            taken[slot] = true;
            assigned.put(key, State.ofIndex(slot));
        }
        return Map.copyOf(assigned);
    }

    /**
     * A generation-side view: which state each block owns, plus the implied note-block
     * variant list a pack must emit.
     */
    public static Map<String, String> blockStateVariants(Collection<NamespacedKey> keys) {
        Map<String, String> variants = new LinkedHashMap<>();
        allocate(keys).forEach((key, state) ->
            variants.put(state.blockStateString(), key.namespace() + ":block/" + key.path()));
        return Map.copyOf(variants);
    }

    /** How many of the 800 states a given key set consumes. */
    public static int utilisation(Collection<NamespacedKey> keys) {
        return allocate(keys).size();
    }

    /** The state assigned to one key when it is the only block present, for ad-hoc lookups. */
    public static Optional<State> stateOf(NamespacedKey key) {
        return Optional.of(allocate(List.of(key)).get(key));
    }
}
