package dev.uas.paper.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Every thread hop the engine makes, behind one interface.
 *
 * <p>The point is not abstraction for its own sake. Paper and Folia disagree about
 * what "run this later" even means:
 *
 * <ul>
 *   <li>On Paper there is one main thread. Anything that touches the world must be
 *       posted there, and the natural unit of work is "the whole server's per-tick
 *       work". 500 players means 500 players' worth of that work in one thread.</li>
 *   <li>On Folia there is no main thread. Work is addressed to the region that owns
 *       the location, or to the entity itself; two players in different regions run
 *       their per-player work <em>at the same time, on different threads</em>. Sending
 *       it to a "global" thread instead deliberately serialises it, which is how a
 *       Folia plugin ends up as slow as a Paper one on the same hardware.</li>
 * </ul>
 *
 * <p>So the interface is expressed in terms of what the caller actually knows -- a
 * player, a location, or "nothing in particular" -- and each implementation maps that
 * onto the cheapest correct mechanism for its platform. {@link #runForPlayer} in
 * particular is the one that differs most and the one that carries the scaling
 * argument: it is region-affine on Folia and main-threaded on Paper.
 *
 * <p>Nothing here is allowed to block. {@link #runAsync} exists for the disk- and
 * CPU-heavy work (pack compilation, hash verification) that must never run on a tick
 * thread on either platform.
 */
public interface UasScheduler {

    ServerPlatform platform();

    /**
     * Runs {@code work} on the thread that owns {@code player}'s entity.
     *
     * <p>This is the only correct way to touch a player on Folia: resource-pack pushes,
     * plugin-message sends and inventory edits all require the entity's owning thread.
     * On Paper it collapses to the main thread.
     *
     * <p>Implementations must be safe to call from any thread, including a Netty event
     * loop, and must not throw when the player has disconnected -- a scheduling attempt
     * against a dead entity is normal traffic, not an error.
     *
     * @return true when the work was accepted; false when the player is gone or retired
     *     and the work will never run
     */
    boolean runForPlayer(Player player, Runnable work);

    /**
     * Runs {@code work} on the thread that owns {@code location}'s region.
     *
     * <p>Used for world edits: placing a custom block's backing state, spawning or
     * removing the display entity that carries its geometry. On Paper this is the main
     * thread; on Folia it is the region thread, so two players in distant regions can
     * both place blocks without contending.
     */
    boolean runAt(Location location, Runnable work);

    /**
     * Runs {@code work} on the single global thread.
     *
     * <p>For genuinely global, non-region state: the reload coordinator, the periodic
     * registry sweep. Deliberately not the general-purpose "run this on the server"
     * method -- using it for per-player work is what makes a Folia plugin single
     * threaded again.
     */
    boolean runGlobal(Runnable work);

    /**
     * Runs {@code work} once on the global thread, {@code delayTicks} from now.
     *
     * <p>Used for startup retry (the listening socket may not be bound when a plugin
     * enables) and for periodic maintenance. Delayed rather than blocking: a plugin that
     * sleeps to wait for the server is a plugin that stalls server startup.
     *
     * @return true when the work was scheduled
     */
    boolean runGlobalDelayed(Runnable work, long delayTicks);

    /**
     * Runs {@code work} off all tick threads.
     *
     * <p>Pack compilation and hashing belong here on both platforms. On Folia the
     * AsyncScheduler is also the only context in which a task may be arbitrarily slow
     * without stalling a region.
     */
    boolean runAsync(Runnable work);

    /** True when the calling thread already owns this player's region. */
    boolean ownsCurrentThread(Player player);

    /** Diagnostic view of the scheduling strategy, printed in {@code /uas status}. */
    Map<String, String> describe();

    /** The scheduler that matches the running server. */
    static UasScheduler forPlatform(Plugin plugin) {
        return forPlatform(ServerPlatform.detect(), plugin);
    }

    static UasScheduler forPlatform(ServerPlatform platform, Plugin plugin) {
        return switch (platform) {
            case FOLIA -> new FoliaScheduler(plugin);
            case PAPER -> new PaperScheduler(plugin);
        };
    }

    /**
     * Shared bookkeeping. Both implementations report the same counters so a status
     * dump is comparable across platforms: if {@code rejectedForPlayer} is non-zero on
     * a busy server, per-player work is being dropped and someone needs to know.
     */
    abstract class Base implements UasScheduler {

        protected final Plugin plugin;
        private final java.util.concurrent.atomic.AtomicLong forPlayer =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rejectedForPlayer =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong atLocation =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong global =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong globalDelayed =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong async =
            new java.util.concurrent.atomic.AtomicLong();

        protected Base(Plugin plugin) {
            this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        }

        protected void countForPlayer() {
            forPlayer.incrementAndGet();
        }

        protected void countRejected() {
            rejectedForPlayer.incrementAndGet();
        }

        protected void countAtLocation() {
            atLocation.incrementAndGet();
        }

        protected void countGlobal() {
            global.incrementAndGet();
        }

        protected void countGlobalDelayed() {
            globalDelayed.incrementAndGet();
        }

        protected void countAsync() {
            async.incrementAndGet();
        }

        @Override
        public Map<String, String> describe() {
            Map<String, String> report = new LinkedHashMap<>();
            report.put("platform", platform().displayName());
            report.put("regionised", Boolean.toString(platform().isRegionised()));
            report.put("strategy", strategy());
            report.put("tasks.forPlayer", Long.toString(forPlayer.get()));
            report.put("tasks.rejectedForPlayer", Long.toString(rejectedForPlayer.get()));
            report.put("tasks.atLocation", Long.toString(atLocation.get()));
            report.put("tasks.global", Long.toString(global.get()));
            report.put("tasks.globalDelayed", Long.toString(globalDelayed.get()));
            report.put("tasks.async", Long.toString(async.get()));
            return report;
        }

        /** One line explaining how per-player work is distributed on this platform. */
        protected abstract String strategy();
    }
}
