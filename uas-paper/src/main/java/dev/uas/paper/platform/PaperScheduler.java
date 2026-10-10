package dev.uas.paper.platform;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Scheduling on classic Paper, where one main thread owns all world state.
 *
 * <p>Every world-touching method here collapses onto the same thread, and that is
 * correct rather than a limitation being papered over: on this platform there simply
 * is no second thread that may touch a player or a block. Recording that honestly is
 * what lets {@code /uas status} explain the difference between the two deployments
 * instead of pretending the scheduler is platform-neutral.
 *
 * <p>The one genuine parallelism available -- {@link #runAsync} -- is reserved for work
 * that touches no world state at all: compiling a pack, hashing an archive, writing
 * geometry JSON to disk. Putting per-player work there on Paper would be a threading
 * bug, not an optimisation, which is why it is not offered as one.
 */
final class PaperScheduler extends UasScheduler.Base {

    PaperScheduler(Plugin plugin) {
        super(Objects.requireNonNull(plugin, "plugin"));
    }

    @Override
    public ServerPlatform platform() {
        return ServerPlatform.PAPER;
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
        // Paper's runTask throws if the plugin is disabled mid-shutdown; a shutdown is
        // not a failure to report, so it is swallowed here and counted as a rejection.
        try {
            Bukkit.getScheduler().runTask(plugin, work);
            return true;
        } catch (IllegalStateException disabling) {
            countRejected();
            return false;
        }
    }

    @Override
    public boolean runAt(Location location, Runnable work) {
        Objects.requireNonNull(work, "work");
        countAtLocation();
        try {
            Bukkit.getScheduler().runTask(plugin, work);
            return true;
        } catch (IllegalStateException disabling) {
            countRejected();
            return false;
        }
    }

    @Override
    public boolean runGlobal(Runnable work) {
        Objects.requireNonNull(work, "work");
        countGlobal();
        try {
            Bukkit.getScheduler().runTask(plugin, work);
            return true;
        } catch (IllegalStateException disabling) {
            countRejected();
            return false;
        }
    }

    @Override
    public boolean runGlobalDelayed(Runnable work, long delayTicks) {
        Objects.requireNonNull(work, "work");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative");
        }
        countGlobalDelayed();
        try {
            Bukkit.getScheduler().runTaskLater(plugin, work, delayTicks);
            return true;
        } catch (IllegalStateException disabling) {
            countRejected();
            return false;
        }
    }

    @Override
    public boolean runAsync(Runnable work) {
        Objects.requireNonNull(work, "work");
        countAsync();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
            return true;
        } catch (IllegalStateException disabling) {
            countRejected();
            return false;
        }
    }

    @Override
    public boolean ownsCurrentThread(Player player) {
        // Ignored deliberately: on Paper "the owning thread" is the main thread, which
        // the caller can test for itself; claiming per-player affinity would be a lie.
        return Bukkit.getServer().isPrimaryThread();
    }

    @Override
    protected String strategy() {
        return "per-player work is serialised onto the single main thread; "
            + "concurrency is limited to async work that touches no world state";
    }
}
