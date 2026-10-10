package dev.uas.geyser;

import dev.uas.core.memory.MemoryBudget;
import dev.uas.core.memory.MemoryProfile;
import dev.uas.core.ui.UnifiedUIManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

/**
 * The Geyser extension: registers the catalog's blocks with Geyser and hands the client a
 * compiled Bedrock pack.
 *
 * <p>Two events do all the work, and the order between them is fixed by Geyser rather than
 * chosen here:
 *
 * <ol>
 *   <li>{@code GeyserDefineCustomBlocksEvent} -- the only moment custom blocks can be
 *       declared. Geyser folds the block set into what it tells the client at login, so a
 *       block registered later is invisible until the player reconnects. The engine
 *       therefore registers from the catalog as-is and reports what it could not
 *       register, rather than trying to patch later.</li>
 *   <li>{@code GeyserDefineResourcePacksEvent} -- where the generated pack is handed to
 *       Geyser. The pack is built here, on this thread, with the same disk-backed
 *       low-memory compiler the Java side uses, and then registered as a zip.</li>
 * </ol>
 *
 * <p>The handlers are {@code @Subscribe}-annotated methods on the extension class, which is
 * how Geyser discovers extension listeners; there is no registration call and no bus
 * handle to keep, so the extension has no lifecycle state to leak.
 *
 * <h2>How the catalog gets filled</h2>
 *
 * <p>{@link #catalog()} is the seam. When Geyser runs inside the same JVM as the Paper
 * plugin (Geyser-Spigot), the plugin populates it and both renderers compile the same
 * declaration objects -- which is the only arrangement that guarantees the Java and Bedrock
 * views cannot drift. For a standalone Geyser there is no shared memory, and correctness
 * then requires the declaration to be serialised deliberately; see {@link UasCatalog} for
 * why that is left as an explicit follow-up rather than half-implemented here.
 */
public final class UasGeyserExtension implements Extension {

    /** Process-wide catalog, so a co-located plugin can populate it before Geyser starts. */
    private static final UasCatalog SHARED_CATALOG = new UasCatalog();

    private final AtomicLong compiledPacks = new AtomicLong();
    private final AtomicLong registeredBlocks = new AtomicLong();

    /**
     * The declarations both renderers compile from.
     *
     * <p>A static holder, deliberately: the extension instance is created by Geyser's loader
     * at a moment a co-located plugin does not control, so a per-instance catalog would
     * have nothing in it. The shared instance is the only way the plugin can hand over the
     * declarations before Geyser reads them.
     */
    public static UasCatalog sharedCatalog() {
        return SHARED_CATALOG;
    }

    /** The catalog this extension instance will compile. */
    public UasCatalog catalog() {
        return SHARED_CATALOG;
    }

    @Subscribe
    public void onDefineCustomBlocks(GeyserDefineCustomBlocksEvent event) {
        if (SHARED_CATALOG.isEmpty()) {
            logger().info("UAS catalog is empty; no custom Bedrock blocks to register. "
                + "A co-located UAS plugin populates it before Geyser starts.");
            return;
        }
        GeyserBlockBridge.Registered registered =
            GeyserBlockBridge.registerAll(event, SHARED_CATALOG.blocks());
        registeredBlocks.set(registered.blocks());

        logger().info("UAS registered " + registered.blocks() + " custom Bedrock block(s)");
        for (String note : registered.notes()) {
            // Notes are warnings, not failures: each one names a conversion that lost a
            // detail, which is exactly what an operator needs to see before a player does.
            logger().warning("UAS conversion: " + note);
        }
    }

    @Subscribe
    public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
        if (SHARED_CATALOG.isEmpty()) {
            return;
        }
        Path root = dataFolder().resolve("pack");
        Path scratch = dataFolder().resolve("scratch");
        try {
            Files.createDirectories(root);
            Files.createDirectories(scratch);
            MemoryProfile profile = MemoryProfile.detectRunningJvm();
            GeyserAssetWriter writer = new GeyserAssetWriter(
                root, scratch, MemoryBudget.forProfile(profile, Runtime.getRuntime().maxMemory()),
                new UnifiedUIManager());

            GeyserAssetWriter.Written written = writer.write(SHARED_CATALOG);
            compiledPacks.incrementAndGet();

            // The pack is registered as a zip the client downloads from Geyser itself: a
            // Bedrock client speaks no Minecraft protocol, so the multiplexer's HTTP route
            // is never involved and a URL would be a lie.
            ResourcePack pack = ResourcePack.create(PackCodec.path(written.archive()));
            event.register(pack);

            logger().info("UAS compiled the Bedrock pack: " + written.describe());
            for (String note : written.notes()) {
                logger().info("UAS pack note: " + note);
            }
        } catch (IOException | RuntimeException failure) {
            // A failed pack must not take Geyser down: without it players see vanilla
            // textures for custom blocks, which is degraded but playable.
            logger().error("UAS could not compile the Bedrock resource pack; custom blocks "
                + "will render with vanilla textures", failure);
        }
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        logger().info("UAS Geyser bridge ready: " + describe());
    }

    /** Diagnostics for the Geyser console. */
    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        SHARED_CATALOG.describe().forEach(report::put);
        report.put("geyser.registeredBlocks", Long.toString(registeredBlocks.get()));
        report.put("geyser.compiledPacks", Long.toString(compiledPacks.get()));
        report.put("geyser.memoryProfile", MemoryProfile.detectRunningJvm().configName());
        report.put("geyser.packTransport", "geyser-managed zip (not the HTTP multiplexer)");
        return report;
    }
}
