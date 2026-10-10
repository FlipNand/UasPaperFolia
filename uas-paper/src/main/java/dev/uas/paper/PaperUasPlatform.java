package dev.uas.paper;

import dev.uas.api.NamespacedKey;
import dev.uas.api.block.BlockBackend;
import dev.uas.api.block.CustomBlock;
import dev.uas.core.UasEngine;
import dev.uas.netty.http.SniffStats;
import dev.uas.netty.pipeline.MultiplexerInstaller;
import dev.uas.paper.block.JavaBlockPlacer;
import dev.uas.paper.net.NettyPipelineInjector;
import dev.uas.paper.net.UasChannelBridge;
import dev.uas.paper.pack.PackPusher;
import dev.uas.paper.platform.PlayerWorkQueue;
import dev.uas.paper.platform.ServerPlatform;
import dev.uas.paper.platform.UasScheduler;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The one object a Paper or Folia plugin holds.
 *
 * <p>It assembles the engine, picks the scheduler for the platform it is running on, and
 * attaches the asset multiplexer to the server's own listening socket. Every other
 * component in the engine is deliberately platform-free; this is the seam where the
 * platform is allowed to matter, and it is a single file so that "what does this engine
 * do differently on Folia?" has one honest answer.
 *
 * <h2>Portless by construction</h2>
 *
 * <p>{@link UasEngine#start()} is never called here. That method binds a standalone
 * listener for diagnostics and integration tests; on a real server the engine must
 * instead attach to the socket the server already owns. So the multiplexer is
 * constructed directly and injected, and {@code engine.server()} stays empty on purpose.
 * A status dump that shows {@code serverBound=false} next to
 * {@code net.hookedChannels=1} is the correct, expected shape on Paper -- not a missing
 * step.
 *
 * <h2>Why injection is retried</h2>
 *
 * <p>A plugin's {@code onEnable} can run before the server has bound, and Paper installs
 * its acceptor from a task posted to the event loop. Rather than sleep or assume, the
 * first attempt is made immediately and, if no listening channel was found, retried on
 * the global thread a bounded number of times. Each attempt leaves a trace, so a build
 * where the accessor names changed logs exactly which hop failed instead of quietly
 * serving no packs.
 */
public final class PaperUasPlatform implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(PaperUasPlatform.class.getName());

    private final Plugin plugin;
    private final UasPaperConfig config;
    private final ServerPlatform platform;
    private final UasScheduler scheduler;
    private final PlayerWorkQueue workQueue;
    private final UasEngine engine;
    private final MultiplexerInstaller multiplexer;
    private final NettyPipelineInjector injector;
    private final UasChannelBridge bridge;
    private final PackPusher packPusher;
    private final JavaBlockPlacer placer;

    /** Players known to be on Bedrock, reported by the Geyser bridge. */
    private final Set<UUID> bedrockPlayers = ConcurrentHashMap.newKeySet();

    /** Live block placements, so a reload can undo exactly what this engine placed. */
    private final Map<NamespacedKey, JavaBlockPlacer.Applied> placements = new ConcurrentHashMap<>();

    private final AtomicInteger hookAttempts = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean hooked = new AtomicBoolean();

    private PaperUasPlatform(Plugin plugin, UasPaperConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.platform = ServerPlatform.detect();
        this.scheduler = UasScheduler.forPlatform(platform, plugin);
        this.workQueue = new PlayerWorkQueue(scheduler, config.maxTrackedPlayers());
        this.engine = UasEngine.builder()
            .profile(config.memoryProfile())
            .workDirectory(plugin.getDataFolder().toPath().resolve("cache"))
            .verifyPacksOnRegister(config.verifyPacksOnRegister())
            .maxTrackedPlayers(config.maxTrackedPlayers())
            .allocatorTuning(UasEngine.tuningFor(config.memoryProfile()))
            .build();
        this.multiplexer = new MultiplexerInstaller(config.httpConfig(), engine.packs());
        this.injector = new NettyPipelineInjector(multiplexer, Duration.ofSeconds(5));
        this.bridge = new UasChannelBridge(plugin, dev.uas.protocol.PacketRegistry.standard(), workQueue);
        this.packPusher = new PackPusher(
            engine, scheduler, workQueue, config.publicBaseUrl(), config.packPrompt(), config.packRequired());
        this.placer = new JavaBlockPlacer(plugin, config.ownershipGuard());
    }

    public static PaperUasPlatform create(Plugin plugin, UasPaperConfig config) {
        return new PaperUasPlatform(
            Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(config, "config"));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the platform: allocator tuning, injection, channel, and the pack sweep. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // Netty reads these system properties when it first builds an allocator, which is
        // before the server has accepted anything, so they must be set now rather than at
        // first use.
        engine.applyAllocatorSystemProperties();
        bridge.register();
        attemptInjection();
        scheduleSweep();

        LOGGER.info("UAS started on " + platform.displayName()
            + " with the '" + config.memoryProfile().configName() + "' memory profile");
        // Checked here, unconditionally, rather than only once a channel is hooked: a wrong
        // port is a configuration mistake and an operator should hear about it even on a
        // build where the multiplexer failed to attach for an unrelated reason.
        config.portMismatchWarning(plugin.getServer().getPort()).ifPresent(LOGGER::warning);
        if (config.logStartupTrace()) {
            LOGGER.info("UAS status:" + System.lineSeparator() + statusReport());
        }
    }

    private void attemptInjection() {
        int attempt = hookAttempts.incrementAndGet();
        NettyPipelineInjector.Report report = injector.install();
        for (String line : report.trace()) {
            LOGGER.log(config.logStartupTrace() ? Level.INFO : Level.FINE, "UAS injection: {0}", line);
        }
        if (report.attached()) {
            hooked.set(true);
            LOGGER.info("UAS attached the asset multiplexer to the Minecraft listener ("
                + report.summarise() + "); packs are served on the game port, no extra port is open");
            return;
        }
        if (report.shouldRetry() && attempt < config.maxHookAttempts()) {
            LOGGER.info("UAS found no listening channel yet (attempt " + attempt + " of "
                + config.maxHookAttempts() + "); retrying in " + config.hookRetryIntervalTicks() + " ticks");
            scheduler.runGlobalDelayed(this::attemptInjection, config.hookRetryIntervalTicks());
            return;
        }
        LOGGER.severe("UAS could NOT attach to the Minecraft listener after " + attempt
            + " attempt(s). Asset streaming over the game port is unavailable. Trace:"
            + System.lineSeparator() + report.describeTrace());
    }

    /**
     * Periodically re-pushes the pack to players who are behind.
     *
     * <p>Runs on the global thread but does almost nothing there: it compares one
     * generation counter per player and hands the actual push to each player's owning
     * thread. On Folia that spreads the work across regions, which is the difference
     * between a pack rotation costing one thread or costing N.
     */
    private void scheduleSweep() {
        scheduler.runGlobalDelayed(this::sweep, config.packSweepIntervalTicks());
    }

    private void sweep() {
        try {
            packPusher.pruneOffline();
            if (engine.packs().generation() == 0) {
                return;
            }
            int queued = packPusher.pushStaleToAll();
            if (queued > 0) {
                LOGGER.log(Level.FINE, "UAS queued {0} pack push(es) after a generation change", queued);
            }
        } finally {
            // A self-rescheduling task must never die: if it threw once and stopped, packs
            // would silently stop being delivered.
            scheduleSweep();
        }
    }

    /**
     * Re-attempts pipeline injection and returns a fresh report.
     *
     * <p>Exposed as an operation rather than kept private because the most common real-world
     * failure is a plugin enabling before the server has bound its socket, and the fix has to be
     * reachable without a restart. Safe at any time: an already-hooked channel is counted as
     * hooked and left alone, so re-running cannot stack two interceptors on one socket.
     */
    public NettyPipelineInjector.Report reinject() {
        int attempt = hookAttempts.incrementAndGet();
        NettyPipelineInjector.Report report = injector.install();
        if (report.attached()) {
            hooked.set(true);
        }
        for (String line : report.trace()) {
            LOGGER.log(Level.INFO, "UAS injection (attempt {0}): {1}", new Object[] {attempt, line});
        }
        return report;
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        bridge.unregister();
        int detached = injector.uninstall();
        engine.close();
        LOGGER.info("UAS stopped; detached the multiplexer from " + detached + " channel(s)");
    }

    // -------------------------------------------------------------------- players

    public void onPlayerJoin(Player player) {
        Objects.requireNonNull(player, "player");
        bridge.sendHello(player, UasChannelBridge.serverCapabilities(), plugin.getName());
        packPusher.pushIfStale(player);
    }

    public void onPlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        workQueue.forget(id);
        packPusher.forget(id);
        engine.forgetPlayer(id.toString());
        bedrockPlayers.remove(id);
    }

    /** Records that a player is on Bedrock, so UI plans target the right surface. */
    public void markBedrock(Player player, boolean bedrock) {
        Objects.requireNonNull(player, "player");
        if (bedrock) {
            bedrockPlayers.add(player.getUniqueId());
            engine.ui().setTarget(player.getUniqueId().toString(),
                dev.uas.core.ui.UiTarget.BEDROCK);
        } else {
            bedrockPlayers.remove(player.getUniqueId());
            engine.ui().setTarget(player.getUniqueId().toString(),
                dev.uas.core.ui.UiTarget.JAVA);
        }
    }

    public boolean isBedrock(Player player) {
        return bedrockPlayers.contains(player.getUniqueId());
    }

    /** The client family the UI planner should target for this player. */
    public dev.uas.core.ui.UiTarget targetFor(Player player) {
        return isBedrock(player) ? dev.uas.core.ui.UiTarget.BEDROCK : dev.uas.core.ui.UiTarget.JAVA;
    }

    /** Forces a pack push now, bypassing the generation check. */
    public boolean pushPackNow(Player player) {
        return packPusher.pushNow(player);
    }

    // --------------------------------------------------------------------- blocks

    /**
     * Registers custom blocks and assigns their note-block states in one pass.
     *
     * <p>The whole set is registered before any state is assigned, because the allocator
     * is only collision-free relative to the complete key set.
     *
     * @return each block's resolved backend
     */
    public Map<NamespacedKey, BlockBackend> registerBlocks(Collection<CustomBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        Map<NamespacedKey, BlockBackend> resolved = new LinkedHashMap<>();
        for (CustomBlock block : blocks) {
            resolved.put(block.key(), engine.registerBlock(block));
        }
        placer.assignStates(resolved.keySet());
        return Map.copyOf(resolved);
    }

    /**
     * Places a custom block. Runs on the location's owning region thread.
     *
     * <p>Deliberately asynchronous from the caller's point of view: the caller may be on
     * the Netty thread or the global thread, and neither may touch a block on Folia.
     */
    public boolean placeBlock(org.bukkit.Location location, CustomBlock block) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(block, "block");
        Optional<BlockBackend> backend = engine.blocks().backendFor(block.key());
        if (backend.isEmpty()) {
            LOGGER.warning("refusing to place unregistered block '" + block.key() + "'");
            return false;
        }
        return scheduler.runAt(location, () -> {
            JavaBlockPlacer.Applied applied = placer.apply(location, block, backend.get());
            JavaBlockPlacer.Applied previous = placements.put(block.key(), applied);
            if (previous != null) {
                LOGGER.fine(() -> "replaced the previous placement of " + block.key());
            }
        });
    }

    /** Removes a placed block, if one is tracked for that key. */
    public boolean removeBlock(NamespacedKey key) {
        Objects.requireNonNull(key, "key");
        JavaBlockPlacer.Applied applied = placements.get(key);
        if (applied == null) {
            return false;
        }
        org.bukkit.Location location = applied.location();
        if (location == null) {
            placements.remove(key);
            return false;
        }
        return scheduler.runAt(location, () -> {
            placer.remove(applied);
            placements.remove(key);
        });
    }

    // -------------------------------------------------------------------- accessors

    public UasEngine engine() {
        return engine;
    }

    public ServerPlatform platform() {
        return platform;
    }

    public UasScheduler scheduler() {
        return scheduler;
    }

    public PlayerWorkQueue workQueue() {
        return workQueue;
    }

    public UasChannelBridge bridge() {
        return bridge;
    }

    public PackPusher packPusher() {
        return packPusher;
    }

    public JavaBlockPlacer placer() {
        return placer;
    }

    public MultiplexerInstaller multiplexer() {
        return multiplexer;
    }

    public UasPaperConfig config() {
        return config;
    }

    /** True when the multiplexer is attached to the server's listening socket. */
    public boolean isHooked() {
        return hooked.get();
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("platform", platform.displayName());
        report.put("platform.regionised", Boolean.toString(platform.isRegionised()));
        report.put("plugin", plugin.getName());
        report.put("portless", "true");
        report.put("hooked", Boolean.toString(hooked.get()));
        report.put("hookAttempts", Integer.toString(hookAttempts.get()));
        report.put("bedrockPlayers", Integer.toString(bedrockPlayers.size()));
        report.put("placedBlocks", Integer.toString(placements.size()));

        scheduler.describe().forEach((key, value) -> report.put("scheduler." + key, value));
        workQueue.describe().forEach((key, value) -> report.put("queue." + key, value));
        bridge.describe().forEach((key, value) -> report.put("bridge." + key, value));
        packPusher.describe().forEach((key, value) -> report.put("push." + key, value));
        placer.describe().forEach((key, value) -> report.put("block." + key, value));
        engine.describe().forEach((key, value) -> report.put("engine." + key, value));

        SniffStats stats = multiplexer.stats();
        report.put("sniff.connections", Long.toString(stats.connections()));
        report.put("sniff.minecraftConnections", Long.toString(stats.minecraftConnections()));
        report.put("sniff.httpConnections", Long.toString(stats.httpConnections()));
        report.put("sniff.ambiguousBytes", Long.toString(stats.ambiguousBytes()));
        report.put("sniff.timeoutClosed", Long.toString(stats.timeoutClosed()));
        return report;
    }

    public String statusReport() {
        StringBuilder builder = new StringBuilder("UAS status (" + platform.displayName() + ")\n");
        describe().forEach((key, value) ->
            builder.append("  ").append(key).append(": ").append(value).append('\n'));
        return builder.toString();
    }
}
