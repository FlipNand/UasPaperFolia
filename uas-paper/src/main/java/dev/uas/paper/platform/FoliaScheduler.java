package dev.uas.paper.platform;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduling on Folia, where every world slice has its own owning thread.
 *
 * <p>This is the implementation that makes the engine scale with region count rather
 * than with a single thread:
 *
 * <ul>
 *   <li>{@link #runForPlayer} uses the <em>entity</em> scheduler. Two players standing
 *       in different regions have their pack push, their HUD update and their display
 *       entity movement executed on two different threads at the same time. On Paper
 *       the same two operations queue behind each other in one thread; that difference
 *       is the entire reason to run Folia.</li>
 *   <li>{@link #runAt} uses the <em>region</em> scheduler, so a block placement in one
 *       region never blocks a block placement in another.</li>
 *   <li>{@link #runGlobal} is reserved for state that genuinely is global. It is not
 *       the default and it is not used for per-player work, because serialising
 *       per-player work onto the global thread is how a plugin silently throws away
 *       Folia's parallelism.</li>
 * </ul>
 *
 * <p>{@code EntityScheduler#execute} answers {@code false} when the entity has been
 * retired or removed, and the task then never runs. That is not an error -- a player
 * disconnecting mid-push is ordinary traffic -- but it is counted, because a server
 * where every per-player task is rejected is a server whose players are seeing no pack
 * at all, and that is exactly the failure that must not be silent.
 */
final class FoliaScheduler extends UasScheduler.Base {

    /** Delay of one tick, the smallest granularity Folia's scheduler offers. */
    private static final long NEXT_TICK = 1L;

    FoliaScheduler(Plugin plugin) {
        super(Objects.requireNonNull(plugin, "plugin"));
    }

    @Override
    public ServerPlatform platform() {
        return ServerPlatform.FOLIA;
    }

    @Override
    public boolean runForPlayer(Player player, Runnable work) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(work, "work");
        if (!player.isOnline()) {
            countRejected();
            return false;
        }
        countForPlayer();
        // The retirement callback is the only place Folia tells us the work will never
        // run, so the rejection is recorded there rather than guessed from the channel
        // state after the fact.
        return player.getScheduler().execute(plugin, work, this::countRejected, NEXT_TICK);
    }

    @Override
    public boolean runAt(Location location, Runnable work) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(work, "work");
        countAtLocation();
        Bukkit.getServer().getRegionScheduler().execute(plugin, location, work);
        return true;
    }

    @Override
    public boolean runGlobal(Runnable work) {
        Objects.requireNonNull(work, "work");
        countGlobal();
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, work);
        return true;
    }

    @Override
    public boolean runGlobalDelayed(Runnable work, long delayTicks) {
        Objects.requireNonNull(work, "work");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative");
        }
        countGlobalDelayed();
        // Folia's runDelayed treats a zero delay as "next tick", which is what a caller
        // passing 0 means; do not translate it to immediate execution.
        Bukkit.getServer().getGlobalRegionScheduler()
            .runDelayed(plugin, task -> work.run(), Math.max(1L, delayTicks));
        return true;
    }

    @Override
    public boolean runAsync(Runnable work) {
        Objects.requireNonNull(work, "work");
        countAsync();
        Bukkit.getServer().getAsyncScheduler().runNow(plugin, task -> work.run());
        return true;
    }

    @Override
    public boolean ownsCurrentThread(Player player) {
        Objects.requireNonNull(player, "player");
        // Region affinity is per-entity on Folia. A false answer means the caller must
        // hop to the owning thread rather than touch the player directly.
        return Bukkit.getServer().isOwnedByCurrentRegion(player);
    }

    @Override
    protected String strategy() {
        return "per-player work is dispatched to the owning region thread; "
            + "N players in N regions are served by N threads in parallel";
    }
}
