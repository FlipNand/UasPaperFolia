package dev.uas.core;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockBackend;
import dev.uas.api.block.BlockSoundGroup;
import dev.uas.api.block.CustomBlock;
import dev.uas.api.sound.CustomSound;
import dev.uas.api.ui.UiSchema;
import dev.uas.core.block.BlockBackendRegistry;
import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import dev.uas.core.pack.LowMemoryPackCompiler;
import dev.uas.core.pack.PackCompileRequest;
import dev.uas.core.pack.PackCompileResult;
import dev.uas.core.pack.PackRegistry;
import dev.uas.core.sound.BlockSoundEvent;
import dev.uas.core.sound.ResolvedSound;
import dev.uas.core.sound.SoundRegistry;
import dev.uas.core.ui.UiRenderPlan;
import dev.uas.core.ui.UiTarget;
import dev.uas.core.ui.UnifiedUIManager;
import dev.uas.netty.AssetStreamServer;
import dev.uas.netty.memory.NettyAllocatorTuning;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The engine facade: one object a plugin holds, and the only place the modules meet.
 *
 * <p>Everything below is independently useful, but a plugin should not have to
 * assemble a memory budget, a pack compiler, a pack registry, three registries and an
 * allocator profile in the right order to get anything done. This class does that
 * assembly once, exposes the pieces that plugins legitimately need to call, and keeps
 * the wiring decisions -- which profile, which allocator geometry, when to recompile
 * -- in a single auditable place.
 *
 * <p>Deliberately not a singleton and deliberately free of any Paper, Geyser or
 * Bukkit type. The engine is started by a platform adapter (paper, geyser) that owns
 * the lifecycle, and the same engine is constructed in tests with a plain loopback
 * server.
 */
public final class UasEngine implements AutoCloseable {

    private final MemoryProfile profile;
    private final MemoryBudget budget;
    private final PackRegistry packs;
    private final SoundRegistry sounds;
    private final UnifiedUIManager ui;
    private final BlockBackendRegistry blocks;
    private final Path workDirectory;
    private final String host;
    private final int requestedPort;
    private final NettyAllocatorTuning allocatorTuning;

    /**
     * Set once {@link #start()} binds. The netty server cannot exist before it binds,
     * so the engine holds "not started" rather than pretending to hold a server.
     */
    private volatile AssetStreamServer server;
    private volatile long packsCompiled;

    private UasEngine(Builder builder) {
        this.profile = builder.profile;
        this.budget = MemoryBudget.forProfile(builder.profile, Runtime.getRuntime().maxMemory());
        this.workDirectory = builder.workDirectory;
        this.host = builder.host;
        this.requestedPort = builder.port;
        this.packs = new PackRegistry(builder.verifyPacksOnRegister);
        this.sounds = new SoundRegistry(builder.random);
        this.ui = new UnifiedUIManager(builder.maxTrackedPlayers);
        this.blocks = new BlockBackendRegistry(
            builder.availableBackends == null || builder.availableBackends.isEmpty()
                ? Set.of(BlockBackend.DISPLAY_ENTITY, BlockBackend.NOTE_BLOCK_MATRIX,
                    BlockBackend.TRIPWIRE_MATRIX)
                : builder.availableBackends);
        this.allocatorTuning = builder.allocatorTuning != null
            ? builder.allocatorTuning
            : tuningFor(builder.profile);
    }

    /** The allocator geometry that matches a memory profile. */
    public static NettyAllocatorTuning tuningFor(MemoryProfile profile) {
        return switch (profile) {
            case LOW_RAM -> NettyAllocatorTuning.lowRam();
            case BALANCED -> NettyAllocatorTuning.balanced();
            case PERFORMANCE -> NettyAllocatorTuning.performance();
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder in constrained mode, which is what a {@code --uas-low-ram} launch
     * flag or {@code low-memory-mode: true} produces.
     */
    public static Builder lowRam() {
        return new Builder().profile(MemoryProfile.LOW_RAM);
    }

    /** A builder with the profile auto-detected from the running JVM's heap. */
    public static Builder detected() {
        return new Builder().profile(MemoryProfile.detectRunningJvm());
    }

    // ------------------------------------------------------------------ wiring

    public MemoryProfile profile() {
        return profile;
    }

    public MemoryBudget budget() {
        return budget;
    }

    public PackRegistry packs() {
        return packs;
    }

    public SoundRegistry sounds() {
        return sounds;
    }

    public UnifiedUIManager ui() {
        return ui;
    }

    public BlockBackendRegistry blocks() {
        return blocks;
    }

    public Path workDirectory() {
        return workDirectory;
    }

    /** The streaming server, present only after {@link #start()}. */
    public Optional<AssetStreamServer> server() {
        return Optional.ofNullable(server);
    }

    public boolean isStarted() {
        return server != null;
    }

    /**
     * Binds the streaming server. Idempotent.
     *
     * <p>Calling this is what makes packs reachable. On Paper the server channel is
     * then hooked into the vanilla listener, so the pack is served from the game port
     * and no second socket is exposed.
     */
    public UasEngine start() throws InterruptedException {
        if (server == null) {
            synchronized (this) {
                if (server == null) {
                    server = AssetStreamServer.builder()
                        .host(host)
                        .port(requestedPort)
                        .router(packs)
                        .allocatorTuning(allocatorTuning)
                        .bind();
                }
            }
        }
        return this;
    }

    /** The bound port, or the requested one when it is not a nullable value. */
    public int port() {
        AssetStreamServer bound = server;
        return bound != null ? bound.port() : requestedPort;
    }

    /** Closes the streaming server. The engine is single-use after this. */
    @Override
    public void close() {
        AssetStreamServer bound = server;
        if (bound != null) {
            bound.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------ packs

    /**
     * Compiles a pack and registers it, replacing any pack with the same id.
     *
     * <p>The compile is the expensive part and is fully disk-backed, so it is safe to
     * call on a small heap; it is not safe to call on a Netty event loop, because it
     * blocks for as long as compression takes.
     */
    public PackCompileResult compileAndRegister(PackCompileRequest request, String displayName)
        throws IOException {
        Objects.requireNonNull(request, "request");
        LowMemoryPackCompiler compiler = new LowMemoryPackCompiler(budget);
        PackCompileResult result = compiler.compile(request);
        packs.register(result, displayName);
        packsCompiled++;
        return result;
    }

    /** Registers an already-built archive, verifying it if configured to. */
    public void registerPack(String id, Path archive, String sha1, String displayName) {
        packs.register(dev.uas.api.pack.PackSource.of(id, archive, sha1, displayName));
    }

    // ---------------------------------------------------------------- blocks

    /** Registers a block, resolving its backend. Returns the backend chosen. */
    public BlockBackend registerBlock(CustomBlock block) {
        return blocks.register(block);
    }

    /** Registers a custom sound. */
    public CustomSound registerSound(CustomSound sound) {
        return sounds.register(sound);
    }

    /** Resolves which sound a block should play for an interaction. */
    public Optional<ResolvedSound> blockSound(NamespacedKey blockKey, BlockSoundEvent event) {
        return blocks.block(blockKey)
            .map(CustomBlock::sounds)
            .flatMap(group -> sounds.resolveBlockSound(group, event));
    }

    /** Convenience for a block's sound group rather than its key. */
    public Optional<ResolvedSound> blockSound(BlockSoundGroup group, BlockSoundEvent event) {
        return sounds.resolveBlockSound(group, event);
    }

    // --------------------------------------------------------------------- ui

    public UiRenderPlan registerUi(UiSchema schema) {
        ui.register(schema);
        return ui.planFor(schema, UiTarget.JAVA);
    }

    public UiRenderPlan openUi(String playerId, NamespacedKey schemaKey) {
        return ui.open(playerId, schemaKey);
    }

    public void closeUi(String playerId) {
        ui.close(playerId);
    }

    /** Call on disconnect: drops both the open UI and the remembered client family. */
    public void forgetPlayer(String playerId) {
        ui.forget(playerId);
    }

    // ------------------------------------------------------------------ admin

    /** Everything the engine decided, in a form a startup log or {@code /uas status} can print. */
    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("profile", profile.configName());
        report.put("constrained", Boolean.toString(profile.isConstrained()));
        budget.describe().forEach((key, value) -> report.put("budget." + key, value));
        packs.describe().forEach((key, value) -> report.put("packs." + key, value));
        blocks.describe().forEach((key, value) -> report.put("blocks." + key, value));
        sounds.describe().forEach((key, value) -> report.put("sounds." + key, value));
        ui.describe().forEach((key, value) -> report.put("ui." + key, value));
        report.put("packsCompiled", Long.toString(packsCompiled));
        AssetStreamServer bound = server;
        report.put("serverBound", Boolean.toString(bound != null));
        report.put("serverHooked", Boolean.toString(bound != null && bound.isHooked()));
        report.put("port", Integer.toString(port()));
        if (bound != null) {
            // The counters Netty records at the socket, which are the only honest
            // answer to "is the sniffer actually seeing traffic, or did nothing attach?"
            var stats = bound.stats();
            report.put("sniff.connections", Long.toString(stats.connections()));
            report.put("sniff.minecraftConnections", Long.toString(stats.minecraftConnections()));
            report.put("sniff.httpConnections", Long.toString(stats.httpConnections()));
            report.put("sniff.ambiguousBytes", Long.toString(stats.ambiguousBytes()));
            report.put("sniff.timeoutClosed", Long.toString(stats.timeoutClosed()));
        }
        return report;
    }

    /**
     * A human-readable status block, deliberately multi-line: an operator diagnosing a
     * pack problem wants to read this, not parse it.
     */
    public String statusReport() {
        StringBuilder builder = new StringBuilder("UAS engine status\n");
        describe().forEach((key, value) -> builder.append("  ").append(key).append(": ").append(value).append('\n'));
        return builder.toString();
    }

    /** Configures the JVM system properties Netty reads, before any allocator is built. */
    public void applyAllocatorSystemProperties() {
        tuningFor(profile).applySystemProperties();
    }

    /** Mutable builder for {@link UasEngine}. */
    public static final class Builder {
        private MemoryProfile profile = MemoryProfile.detectRunningJvm();
        private String host = "0.0.0.0";
        private int port = 0;
        private Path workDirectory = Path.of(System.getProperty("java.io.tmpdir"), "uas");
        private Set<BlockBackend> availableBackends;
        private boolean verifyPacksOnRegister;
        private int maxTrackedPlayers = UnifiedUIManager.DEFAULT_MAX_TRACKED_PLAYERS;
        private NettyAllocatorTuning allocatorTuning;
        private java.util.random.RandomGenerator random = java.util.random.RandomGenerator.getDefault();

        private Builder() {
        }

        public Builder profile(MemoryProfile value) {
            this.profile = Objects.requireNonNull(value, "profile");
            return this;
        }

        /** Chooses the profile from the JVM heap, honouring an explicit override. */
        public Builder profileFromHeap(boolean forcedLowRam) {
            this.profile = MemoryProfile.detect(Runtime.getRuntime().maxMemory(), forcedLowRam);
            return this;
        }

        public Builder host(String value) {
            this.host = Objects.requireNonNull(value, "host");
            return this;
        }

        /**
         * The port to bind when running standalone. On Paper this is unused: the
         * multiplexer is attached to the existing game listener instead.
         */
        public Builder port(int value) {
            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException("port must be 0..65535, got " + value);
            }
            this.port = value;
            return this;
        }

        public Builder workDirectory(Path value) {
            this.workDirectory = Objects.requireNonNull(value, "workDirectory");
            return this;
        }

        public Builder availableBackends(Set<BlockBackend> value) {
            this.availableBackends = value == null ? null : Set.copyOf(value);
            return this;
        }

        public Builder verifyPacksOnRegister(boolean value) {
            this.verifyPacksOnRegister = value;
            return this;
        }

        public Builder maxTrackedPlayers(int value) {
            this.maxTrackedPlayers = value;
            return this;
        }

        public Builder allocatorTuning(NettyAllocatorTuning value) {
            this.allocatorTuning = value;
            return this;
        }

        public Builder random(java.util.random.RandomGenerator value) {
            this.random = Objects.requireNonNull(value, "random");
            return this;
        }

        public UasEngine build() {
            return new UasEngine(this);
        }
    }
}
