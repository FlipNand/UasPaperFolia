package dev.uas.core.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.api.NamespacedKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The state matrix has to be exact: a collision means one block renders as another, and
 * a non-reproducible layout means the pack generator and the world disagree about which
 * model a state means.
 */
class NoteBlockStateMapperTest {

    private static NamespacedKey key(String path) {
        return NamespacedKey.parse("uas:" + path);
    }

    @Test
    void theMatrixIsSixteenInstrumentsByTwentyFiveNotesByTwoPoweredStates() {
        assertEquals(800, NoteBlockStateMapper.CAPACITY);
        assertEquals(16 * 25 * 2, NoteBlockStateMapper.CAPACITY);
    }

    @Test
    void stateAndIndexRoundTripAcrossTheWholeMatrix() {
        for (int i = 0; i < NoteBlockStateMapper.CAPACITY; i++) {
            NoteBlockStateMapper.State state = NoteBlockStateMapper.State.ofIndex(i);
            assertEquals(i, state.index(), "index round trip at " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> NoteBlockStateMapper.State.ofIndex(-1));
        assertThrows(IllegalArgumentException.class,
            () -> NoteBlockStateMapper.State.ofIndex(NoteBlockStateMapper.CAPACITY));
    }

    @Test
    void blockStateStringIsTheVanillaForm() {
        NoteBlockStateMapper.State state =
            new NoteBlockStateMapper.State(3, 7, true);
        // Named, because blockstates/note_block.json keys its variants by these names.
        // Index 3 is "hat"; index 0 is the harp, which is what Bukkit calls PIANO.
        assertEquals("instrument=hat,note=7,powered=true", state.blockStateString());
        assertEquals("hat", state.instrumentName());
        assertEquals("harp", new NoteBlockStateMapper.State(0, 0, false).instrumentName());
    }

    @Test
    void theInstrumentTableCoversTheWholeStateSpaceInVanillaOrder() {
        assertEquals(NoteBlockStateMapper.INSTRUMENTS, NoteBlockStateMapper.VANILLA_INSTRUMENTS.size());
        assertEquals("harp", NoteBlockStateMapper.VANILLA_INSTRUMENTS.get(0));
        assertEquals("pling", NoteBlockStateMapper.VANILLA_INSTRUMENTS.get(15));
        assertEquals(NoteBlockStateMapper.INSTRUMENTS,
            new HashSet<>(NoteBlockStateMapper.VANILLA_INSTRUMENTS).size(),
            "instrument names must be unique or two states would render identically");
    }

    @Test
    void allocationUsesEveryStateExactlyOnce() {
        // Fill the matrix completely: the assertion is that no two of 800 keys collide,
        // which a plain hash-and-modulo would fail.
        List<NamespacedKey> keys = IntStream.range(0, NoteBlockStateMapper.CAPACITY)
            .mapToObj(i -> key("block_" + i))
            .toList();

        Map<NamespacedKey, NoteBlockStateMapper.State> assigned = NoteBlockStateMapper.allocate(keys);
        assertEquals(NoteBlockStateMapper.CAPACITY, assigned.size());

        Set<Integer> indices = new HashSet<>();
        for (NoteBlockStateMapper.State state : assigned.values()) {
            assertTrue(indices.add(state.index()),
                "state " + state.blockStateString() + " was assigned twice");
        }
        assertEquals(NoteBlockStateMapper.CAPACITY, indices.size());
    }

    @Test
    void allocationIsIndependentOfInputOrder() {
        List<NamespacedKey> forward = IntStream.range(0, 200).mapToObj(i -> key("b" + i)).toList();
        List<NamespacedKey> reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);

        assertEquals(NoteBlockStateMapper.allocate(forward), NoteBlockStateMapper.allocate(reversed),
            "the mapping must depend only on the set of keys, not the registration order");
    }

    @Test
    void allocationIsStableAcrossRepeatedCalls() {
        List<NamespacedKey> keys = List.of(key("alpha"), key("beta"), key("gamma"));
        assertEquals(NoteBlockStateMapper.allocate(keys), NoteBlockStateMapper.allocate(keys));
    }

    @Test
    void mostKeysLandOnTheirOwnHashSlot() {
        // Probing should be the exception, not the rule: with 200 keys in 800 slots the
        // load factor is 0.25, so the overwhelming majority should need no probe at all.
        List<NamespacedKey> keys = IntStream.range(0, 200).mapToObj(i -> key("k" + i)).toList();
        Map<NamespacedKey, NoteBlockStateMapper.State> assigned = NoteBlockStateMapper.allocate(keys);

        int onHash = 0;
        for (Map.Entry<NamespacedKey, NoteBlockStateMapper.State> entry : assigned.entrySet()) {
            if (entry.getValue().index() == NoteBlockStateMapper.hashSlot(entry.getKey())) {
                onHash++;
            }
        }
        assertTrue(onHash > 180,
            "expected probing to be rare at load factor 0.25, but only " + onHash + " of 200 were unprobed");
    }

    @Test
    void differentKeysGenerallyGetDifferentHashSlots() {
        assertNotEquals(NoteBlockStateMapper.hashSlot(key("stone")), NoteBlockStateMapper.hashSlot(key("dirt")));
    }

    @Test
    void overflowIsRejectedRatherThanSilentlyAliased() {
        List<NamespacedKey> tooMany = IntStream.range(0, NoteBlockStateMapper.CAPACITY + 1)
            .mapToObj(i -> key("overflow_" + i))
            .toList();
        assertThrows(IllegalStateException.class, () -> NoteBlockStateMapper.allocate(tooMany));
    }

    @Test
    void variantsNameTheBlockModelForEachState() {
        List<NamespacedKey> keys = List.of(key("lamp"), key("crate"));
        Map<String, String> variants = NoteBlockStateMapper.blockStateVariants(keys);

        assertEquals(2, variants.size());
        for (Map.Entry<String, String> entry : variants.entrySet()) {
            assertTrue(entry.getKey().startsWith("instrument="), entry.getKey());
            assertTrue(entry.getValue().startsWith("uas:block/"), entry.getValue());
        }
        assertTrue(variants.containsValue("uas:block/lamp"));
        assertEquals(2, NoteBlockStateMapper.utilisation(keys));
    }

    @Test
    void aSingleKeyIsStableThroughTheOneKeyApi() {
        NamespacedKey lone = key("solo");
        assertEquals(NoteBlockStateMapper.hashState(lone), NoteBlockStateMapper.stateOf(lone).orElseThrow());
        assertEquals(NoteBlockStateMapper.hashState(lone), NoteBlockStateMapper.allocate(List.of(lone)).get(lone));
    }
}
