package dev.uas.api.block;

import java.util.List;

/**
 * How a custom block is actually realised on the Java server.
 *
 * <p>Minecraft has no real "custom block" for plugins, so every implementation is a
 * clever reuse of vanilla state. The engine declares a preference order and picks
 * the first backend the running server supports, which keeps packs portable across
 * server builds without the pack author changing anything.
 *
 * <p>The order below is also the default priority: display entities give true
 * per-block geometry and smooth interpolation, note-block matrices are the classic
 * zero-client-mod fallback, and tripwire matrices are the last resort on very old
 * or heavily restricted builds.
 */
public enum BlockBackend {

    /**
     * A real vanilla block (usually note block or mushroom block) for collision and
     * interaction, plus a {@code BlockDisplay} entity carrying the custom geometry.
     * Highest fidelity: non-cube shapes, animated transforms, per-block emission.
     */
    DISPLAY_ENTITY("block_display", 15, true),

    /**
     * A note-block state matrix: 16 instruments x 2 powered states x 25 notes.
     * Geometry is limited to a full cube, but every variant is a genuine vanilla
     * block so there is zero client overhead and no entity count cost.
     */
    NOTE_BLOCK_MATRIX("note_block", 10, false),

    /**
     * A tripwire state matrix: attached/powered/disarmed/side combinations.
     * Retained for servers that disable note blocks or need cheap non-solid states.
     */
    TRIPWIRE_MATRIX("tripwire", 5, false);

    private final String backingBlock;
    private final int fidelity;
    private final boolean requiresEntities;

    BlockBackend(String backingBlock, int fidelity, boolean requiresEntities) {
        this.backingBlock = backingBlock;
        this.fidelity = fidelity;
        this.requiresEntities = requiresEntities;
    }

    /** The vanilla block whose state space this backend consumes. */
    public String backingBlock() {
        return backingBlock;
    }

    /** Higher is better; used to sort a declared preference set. */
    public int fidelity() {
        return fidelity;
    }

    public boolean requiresEntities() {
        return requiresEntities;
    }

    /** Backends that can represent non-cube geometry. */
    public boolean supportsCustomGeometry() {
        return this == DISPLAY_ENTITY;
    }

    /** Backends that can represent per-block light emission. */
    public boolean supportsLightEmission() {
        return this == DISPLAY_ENTITY;
    }

    /** Preference order, best fidelity first. */
    public static List<BlockBackend> preferenceOrder() {
        return List.of(DISPLAY_ENTITY, NOTE_BLOCK_MATRIX, TRIPWIRE_MATRIX);
    }
}
