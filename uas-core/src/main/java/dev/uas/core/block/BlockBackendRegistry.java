package dev.uas.core.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockBackend;
import dev.uas.api.block.CustomBlock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which vanilla mechanism each custom block is built on.
 *
 * <p>A custom block on Paper is never really a new block: it is a vanilla block whose
 * state is repurposed, with a plugin-owned model and behaviour layered on top. Which
 * vanilla block that is determines what the custom block <em>can</em> do -- a note
 * block can emit light and animate but cannot carry arbitrary geometry, while display
 * entities carry geometry but are not real blocks and so are not in the collision
 * graph. So the backend is not an implementation detail; it is a capability ceiling,
 * and resolving it up front is what lets the engine tell a plugin author "your shaping
 * request cannot be honoured on this server" at registration instead of letting them
 * discover it in game.
 *
 * <p>Resolution follows the block's own preference order, intersected with what the
 * platform actually offers. Nothing is guessed: if no offered backend can host the
 * block, registration fails loudly.
 */
public final class BlockBackendRegistry {

    private final Set<BlockBackend> available;
    private final Map<NamespacedKey, CustomBlock> blocks = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, BlockBackend> resolved = new ConcurrentHashMap<>();

    public BlockBackendRegistry(Set<BlockBackend> available) {
        Objects.requireNonNull(available, "available");
        if (available.isEmpty()) {
            throw new IllegalArgumentException("at least one block backend must be available");
        }
        this.available = Set.copyOf(available);
    }

    /** The backends the platform reported it can support. */
    public Set<BlockBackend> available() {
        return available;
    }

    /**
     * Resolves and registers a block.
     *
     * @return the backend it was assigned to
     * @throws IllegalArgumentException if no available backend can host the block
     */
    public BlockBackend register(CustomBlock block) {
        Objects.requireNonNull(block, "block");
        BlockBackend backend = block.selectBackend(available).orElseThrow(() ->
            new IllegalArgumentException(
                "custom block '" + block.key() + "' cannot be hosted by any available backend "
                    + available + "; it restricts itself to " + block.backends()));

        // Re-registration is allowed and is how a reload works: the new declaration
        // replaces the old one, and the freshly resolved backend replaces the old one
        // with it, so the two maps can never disagree.
        blocks.put(block.key(), block);
        resolved.put(block.key(), backend);
        return backend;
    }

    public Optional<BlockBackend> backendFor(NamespacedKey key) {
        return Optional.ofNullable(resolved.get(key));
    }

    public Optional<CustomBlock> block(NamespacedKey key) {
        return Optional.ofNullable(blocks.get(key));
    }

    public Set<NamespacedKey> blockKeys() {
        return Set.copyOf(blocks.keySet());
    }

    public int size() {
        return blocks.size();
    }

    /** Blocks grouped by the backend that hosts them. */
    public Map<BlockBackend, List<NamespacedKey>> byBackend() {
        Map<BlockBackend, List<NamespacedKey>> grouped = new LinkedHashMap<>();
        for (BlockBackend backend : BlockBackend.preferenceOrder()) {
            if (available.contains(backend)) {
                grouped.put(backend, new ArrayList<>());
            }
        }
        for (Map.Entry<NamespacedKey, BlockBackend> entry : resolved.entrySet()) {
            grouped.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }
        grouped.values().forEach(list -> list.sort(java.util.Comparator.comparing(NamespacedKey::toString)));
        return grouped;
    }

    /**
     * Blocks whose custom geometry will not be rendered by the backend that hosts them.
     *
     * <p>Not an error: a plugin may deliberately accept a flat-textured approximation on
     * a constrained server. It is a warning, and it needs to be visible.
     */
    public List<NamespacedKey> degradedGeometryBlocks() {
        List<NamespacedKey> degraded = new ArrayList<>();
        for (Map.Entry<NamespacedKey, BlockBackend> entry : resolved.entrySet()) {
            CustomBlock block = blocks.get(entry.getKey());
            if (block == null) {
                continue;
            }
            // A unit cube is expressible on every backend; anything else is shaping that
            // only an entity-backed or model-backed backend can draw.
            boolean wantsGeometry = block.geometry() != null && !block.geometry().isUnitCube();
            if (wantsGeometry && !entry.getValue().supportsCustomGeometry()) {
                degraded.add(entry.getKey());
            }
        }
        degraded.sort(java.util.Comparator.comparing(NamespacedKey::toString));
        return degraded;
    }

    /** Blocks that ask for light emission but are hosted somewhere that cannot give it. */
    public List<NamespacedKey> unsupportedLightBlocks() {
        List<NamespacedKey> unsupported = new ArrayList<>();
        for (Map.Entry<NamespacedKey, BlockBackend> entry : resolved.entrySet()) {
            CustomBlock block = blocks.get(entry.getKey());
            if (block == null) {
                continue;
            }
            int emission = block.physics() == null ? 0 : block.physics().lightEmission();
            if (emission > 0 && !entry.getValue().supportsLightEmission()) {
                unsupported.add(entry.getKey());
            }
        }
        unsupported.sort(java.util.Comparator.comparing(NamespacedKey::toString));
        return unsupported;
    }

    /** Backends that were offered but ended up hosting nothing. */
    public Set<BlockBackend> unusedBackends() {
        EnumSet<BlockBackend> unused = EnumSet.noneOf(BlockBackend.class);
        unused.addAll(available);
        unused.removeAll(resolved.values());
        return unused;
    }

    public void clear() {
        blocks.clear();
        resolved.clear();
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("blocks", Integer.toString(blocks.size()));
        report.put("availableBackends", available.toString());
        report.put("unusedBackends", unusedBackends().toString());
        report.put("degradedGeometry", degradedGeometryBlocks().toString());
        report.put("unsupportedLight", unsupportedLightBlocks().toString());
        return report;
    }
}
