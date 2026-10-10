package dev.uas.paper.block;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockBackend;
import dev.uas.api.block.BoundingBox;
import dev.uas.api.block.CustomBlock;
import dev.uas.core.block.NoteBlockStateMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.Plugin;

/**
 * Places a custom block on the Java side.
 *
 * <p>Two layers, and it is worth being clear about why both exist:
 *
 * <ul>
 *   <li><strong>The block state</strong> is a note block in one of 800 states, chosen by
 *       {@link NoteBlockStateMapper} and rendered as the custom model by the resource
 *       pack. This is the layer that is <em>real</em>: it participates in collision, in
 *       breaking, in redstone, and it survives a restart because it is just world data.</li>
 *   <li><strong>The display entity</strong> is added only when the declared geometry is not
 *       a unit cube. A block state can only ever be a full cube -- the client substitutes
 *       the model, it does not reshape the block -- so anything that is actually shaped
 *       (a lamp post, a crate with a lid, a slab-like piece) needs a
 *       {@code BlockDisplay} whose transform squashes the unit cube into the declared
 *       bounds. This is the {@code DISPLAY_ENTITY} backend's whole purpose, and it is the
 *       reason the backend is a capability ceiling rather than a preference.</li>
 * </ul>
 *
 * <p>Every method here <strong>must run on the thread that owns the block's region</strong>.
 * On Paper that is the main thread; on Folia it is the region thread. It is not merely a
 * convention: Folia's whole safety model is that world state has exactly one owner, and
 * mutating a block from another thread corrupts that state rather than throwing. The
 * engine reaches these methods only through {@code UasScheduler.runAt}, and
 * {@link #assertOwnership} turns a violation into a loud log line in development rather
 * than a corrupted world in production.
 */
public final class JavaBlockPlacer {

    private static final Logger LOGGER = Logger.getLogger(JavaBlockPlacer.class.getName());

    /**
     * Block-state instrument name to Bukkit constant.
     *
     * <p>Not {@code Instrument.valueOf(name.toUpperCase())}: the two vocabularies differ.
     * The block state calls the first value {@code harp}; Bukkit calls it {@code PIANO}.
     * A {@code valueOf} would throw at the first placement, which is exactly the kind of
     * runtime-only surprise this table removes.
     */
    private static final Map<String, Instrument> BUKKIT_INSTRUMENTS = Map.ofEntries(
        Map.entry("harp", Instrument.PIANO),
        Map.entry("basedrum", Instrument.BASS_DRUM),
        Map.entry("snare", Instrument.SNARE_DRUM),
        Map.entry("hat", Instrument.STICKS),
        Map.entry("bass", Instrument.BASS_GUITAR),
        Map.entry("flute", Instrument.FLUTE),
        Map.entry("bell", Instrument.BELL),
        Map.entry("guitar", Instrument.GUITAR),
        Map.entry("chime", Instrument.CHIME),
        Map.entry("xylophone", Instrument.XYLOPHONE),
        Map.entry("iron_xylophone", Instrument.IRON_XYLOPHONE),
        Map.entry("cow_bell", Instrument.COW_BELL),
        Map.entry("didgeridoo", Instrument.DIDGERIDOO),
        Map.entry("bit", Instrument.BIT),
        Map.entry("banjo", Instrument.BANJO),
        Map.entry("pling", Instrument.PLING));

    private final Plugin plugin;
    private final boolean assertThreadOwnership;
    private final Map<NamespacedKey, NoteBlockStateMapper.State> matrix = new ConcurrentHashMap<>();

    public JavaBlockPlacer(Plugin plugin) {
        this(plugin, true);
    }

    public JavaBlockPlacer(Plugin plugin, boolean assertThreadOwnership) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.assertThreadOwnership = assertThreadOwnership;
    }

    /** What a placement produced, and everything needed to undo it. */
    public record Applied(
        NamespacedKey blockKey,
        BlockBackend backend,
        String world,
        int x,
        int y,
        int z,
        NoteBlockStateMapper.State state,
        UUID displayEntity) {

        public Location location() {
            World resolved = Bukkit.getWorld(world);
            return resolved == null ? null : new Location(resolved, x, y, z);
        }

        /** The vanilla block-state string this placement occupies, for the pack generator. */
        public String blockStateString() {
            return state.blockStateString();
        }
    }

    /**
     * Assigns each registered block its own note-block state.
     *
     * <p>Called once with the <em>full</em> set of keys, because the allocation is only
     * collision-free relative to the whole set: assigning one at a time would let the
     * first block take a slot the second one's probe needed. Re-running it is safe and is
     * what a reload does.
     */
    public void assignStates(Collection<NamespacedKey> keys) {
        Objects.requireNonNull(keys, "keys");
        Map<NamespacedKey, NoteBlockStateMapper.State> assigned = NoteBlockStateMapper.allocate(keys);
        matrix.clear();
        matrix.putAll(assigned);
        LOGGER.info("UAS assigned " + assigned.size() + " of " + NoteBlockStateMapper.CAPACITY
            + " note-block states to custom blocks");
    }

    /** The state a block occupies, if it has been assigned one. */
    public Optional<NoteBlockStateMapper.State> stateOf(NamespacedKey key) {
        return Optional.ofNullable(matrix.get(key));
    }

    /** Every assigned state, for a pack generator that needs the variant list. */
    public Map<String, String> blockStateVariants() {
        return NoteBlockStateMapper.blockStateVariants(matrix.keySet());
    }

    /**
     * Places a custom block at a location.
     *
     * <p>Must be called on the region-owning thread.
     *
     * @throws IllegalStateException if no state has been assigned to the block
     */
    public Applied apply(Location location, CustomBlock block, BlockBackend backend) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(backend, "backend");
        assertOwnership(location);

        NoteBlockStateMapper.State state = matrix.get(block.key());
        if (state == null) {
            throw new IllegalStateException(
                "no note-block state assigned to '" + block.key()
                    + "'; call assignStates with the full key set before placing blocks");
        }
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalStateException("cannot place a block at a location with no world");
        }

        Block target = location.getBlock();
        BlockData data = blockDataFor(state, backend);
        // `false` suppresses physics: applying with neighbour updates would cascade
        // redstone and lighting through the whole area for every placed block.
        target.setBlockData(data, false);

        UUID displayEntity = null;
        if (backend.requiresEntities() && shouldDrawEntity(block)) {
            displayEntity = spawnDisplay(location, data, block);
        }

        return new Applied(block.key(), backend, world.getName(),
            location.getBlockX(), location.getBlockY(), location.getBlockZ(), state, displayEntity);
    }

    /** True when the block's declared geometry is not the block state's own full cube. */
    private static boolean shouldDrawEntity(CustomBlock block) {
        return block.geometry() != null && !block.geometry().isUnitCube();
    }

    private BlockData blockDataFor(NoteBlockStateMapper.State state, BlockBackend backend) {
        return switch (backend) {
            case NOTE_BLOCK_MATRIX, DISPLAY_ENTITY -> noteBlockData(state);
            case TRIPWIRE_MATRIX -> tripewireData(state);
        };
    }

    private BlockData noteBlockData(NoteBlockStateMapper.State state) {
        Instrument instrument = BUKKIT_INSTRUMENTS.get(state.instrumentName());
        if (instrument == null) {
            throw new IllegalStateException(
                "no Bukkit instrument mapped to block-state name '" + state.instrumentName() + "'");
        }
        NoteBlock data = (NoteBlock) Bukkit.createBlockData(Material.NOTE_BLOCK);
        data.setInstrument(instrument);
        // Note ids are 0..24; NoteBlockStateMapper guarantees the range.
        data.setNote(new Note(state.note()));
        data.setPowered(state.powered());
        return data;
    }

    private BlockData tripewireData(NoteBlockStateMapper.State state) {
        org.bukkit.block.data.type.Tripwire data =
            (org.bukkit.block.data.type.Tripwire) Bukkit.createBlockData(Material.TRIPWIRE);
        data.setAttached(true);
        data.setDisarmed(false);
        data.setPowered(state.powered());
        // The note index picks which of the three sides are connected, giving the
        // tripwire matrix its extra state space on top of the note block's.
        boolean north = state.note() % 2 == 1;
        boolean south = (state.note() / 2) % 2 == 1;
        boolean east = (state.note() / 4) % 2 == 1;
        boolean west = (state.note() / 8) % 2 == 1;
        data.setFace(org.bukkit.block.BlockFace.NORTH, north);
        data.setFace(org.bukkit.block.BlockFace.SOUTH, south);
        data.setFace(org.bukkit.block.BlockFace.EAST, east);
        data.setFace(org.bukkit.block.BlockFace.WEST, west);
        return data;
    }

    private UUID spawnDisplay(Location location, BlockData data, CustomBlock block) {
        World world = location.getWorld();
        BoundingBox bounds = block.geometry().staticBounds();
        BoundingBox unit = bounds.toBlockSpace();

        // A BlockDisplay draws a full block centred on the entity. Positioning the entity
        // at the centre of the declared box and scaling by the box's size maps the unit
        // cube onto exactly those bounds.
        double centreX = location.getBlockX() + (unit.minX() + unit.maxX()) / 2d;
        double centreY = location.getBlockY() + (unit.minY() + unit.maxY()) / 2d;
        double centreZ = location.getBlockZ() + (unit.minZ() + unit.maxZ()) / 2d;

        Location spawnAt = new Location(world, centreX, centreY, centreZ);
        return world.spawn(spawnAt, BlockDisplay.class, display -> {
            display.setBlock(data);
            // JOML scale only: the entity's own position already carries the translation.
            display.setTransformationMatrix(new org.joml.Matrix4f().scale(
                (float) unit.width(), (float) unit.height(), (float) unit.depth()));
            display.setInterpolationDuration(3);
            display.setInterpolationDelay(0);
            display.setTeleportDuration(3);
            display.setViewRange(1.0f);
            display.setShadowRadius(0f);
            display.setShadowStrength(0f);
            display.setPersistent(false);
            display.setInvulnerable(true);
        }).getUniqueId();
    }

    /**
     * Removes a placement: the block goes back to air and the display entity is dropped.
     *
     * <p>Must be called on the region-owning thread.
     */
    public boolean remove(Applied applied) {
        Objects.requireNonNull(applied, "applied");
        World world = Bukkit.getWorld(applied.world());
        if (world == null) {
            return false;
        }
        Location location = new Location(world, applied.x(), applied.y(), applied.z());
        assertOwnership(location);
        location.getBlock().setBlockData(Material.AIR.createBlockData(), false);
        if (applied.displayEntity() != null) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(applied.displayEntity());
            if (entity != null) {
                entity.remove();
            }
        }
        return true;
    }

    /**
     * Fails loudly when a world mutation happens off its owning thread.
     *
     * <p>Only a development guard: on Folia this situation corrupts state rather than
     * throwing, so the check has to be ours. It is off by default in tests.
     */
    private void assertOwnership(Location location) {
        if (!assertThreadOwnership) {
            return;
        }
        if (!Bukkit.getServer().isOwnedByCurrentRegion(location)) {
            LOGGER.log(Level.SEVERE,
                "a UAS block placement ran off the owning thread at " + location
                    + "; world state must only be mutated by the region that owns it",
                new IllegalStateException("off-region block mutation"));
        }
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("placer.assignedStates", Integer.toString(matrix.size()));
        report.put("placer.matrixCapacity", Integer.toString(NoteBlockStateMapper.CAPACITY));
        report.put("placer.matrixUtilisation", String.format(java.util.Locale.ROOT, "%.1f%%",
            100d * matrix.size() / NoteBlockStateMapper.CAPACITY));
        report.put("placer.instrumentMappings", Integer.toString(BUKKIT_INSTRUMENTS.size()));
        report.put("placer.ownershipGuard", Boolean.toString(assertThreadOwnership));
        return report;
    }

    /** The keys that have been assigned states. */
    public Set<NamespacedKey> assignedKeys() {
        return Set.copyOf(matrix.keySet());
    }
}
