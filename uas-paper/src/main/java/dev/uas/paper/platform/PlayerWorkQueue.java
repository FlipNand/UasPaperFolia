package dev.uas.paper.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Per-player work mailbox that collapses a burst of tasks into one scheduled task.
 *
 * <p>This exists because of a specific, well-known failure mode. The engine has many
 * sources of per-player work -- a pack changed, a HUD value moved, a sound played, a
 * block state synced -- and they fire in bursts. Scheduling each one directly is a
 * trap on <em>both</em> platforms, but for opposite reasons:
 *
 * <ul>
 *   <li>On Paper, each {@code runTask} is a queue insertion into one contended
 *       structure, and a 500-player event that fires three updates per player per tick
 *       is 1500 insertions into a single-threaded queue.</li>
 *   <li>On Folia, each {@code EntityScheduler#execute} carries region bookkeeping and
 *       lands in that region's task queue. Spamming it per update multiplies the
 *       bookkeeping and can push a region's task queue through the tick budget, which
 *       Folia responds to by dropping the region behind -- exactly what the player
 *       perceives as lag.</li>
 * </ul>
 *
 * <p>Both are fixed the same way: the first task for a player arms one scheduled drain,
 * every task after that until the drain runs is appended to that player's mailbox, and
 * the drain executes the whole batch on the correct thread. The number of scheduled
 * tasks per player per tick is therefore one, whatever the update volume, and the
 * {@link #coalesced()} counter makes the saving visible rather than assumed.
 *
 * <p>Work is executed in submission order and a failing task is logged and skipped --
 * one bad listener must not strand the rest of a player's batch or kill the drain,
 * because a dead drain means that player never receives another asset update.
 */
public final class PlayerWorkQueue {

    private static final Logger LOGGER = Logger.getLogger(PlayerWorkQueue.class.getName());

    /** Ceiling on tracked mailboxes; beyond this, empty ones are pruned before adding. */
    public static final int DEFAULT_MAX_TRACKED_PLAYERS = 8192;

    private final UasScheduler scheduler;
    private final int maxTrackedPlayers;
    private final Map<UUID, Mailbox> mailboxes = new ConcurrentHashMap<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong drains = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public PlayerWorkQueue(UasScheduler scheduler) {
        this(scheduler, DEFAULT_MAX_TRACKED_PLAYERS);
    }

    public PlayerWorkQueue(UasScheduler scheduler, int maxTrackedPlayers) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (maxTrackedPlayers <= 0) {
            throw new IllegalArgumentException("maxTrackedPlayers must be > 0");
        }
        this.maxTrackedPlayers = maxTrackedPlayers;
    }

    /**
     * Queues work for a player, on the thread that owns them.
     *
     * <p>Safe to call from any thread. If the player is offline the work is dropped and
     * counted, because there is no thread that could legally run it and holding it
     * would retain a reference to a dead entity.
     */
    public void submit(Player player, Runnable work) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(work, "work");
        if (!player.isOnline()) {
            rejected.incrementAndGet();
            return;
        }
        UUID id = player.getUniqueId();
        Mailbox mailbox = mailboxFor(id);
        mailbox.pending.add(work);
        submitted.incrementAndGet();

        if (mailbox.scheduled.compareAndSet(false, true)) {
            // First task for this player: arm exactly one drain.
            if (!scheduler.runForPlayer(player, () -> drain(player))) {
                // The player vanished between the online check and the scheduling
                // attempt. Release the latch and drop the queued batch rather than
                // leaving a mailbox that can never drain.
                mailbox.scheduled.set(false);
                mailbox.pending.clear();
                rejected.incrementAndGet();
            }
        } else {
            // A drain is already armed; this task rides along with it.
            coalesced.incrementAndGet();
        }
    }

    private Mailbox mailboxFor(UUID id) {
        Mailbox existing = mailboxes.get(id);
        if (existing != null) {
            return existing;
        }
        if (mailboxes.size() >= maxTrackedPlayers) {
            pruneIdleMailboxes();
        }
        return mailboxes.computeIfAbsent(id, ignored -> new Mailbox());
    }

    /**
     * Drops mailboxes that have nothing queued and no drain armed.
     *
     * <p>Called only when the tracked set hits its ceiling, so the common path never
     * pays for the sweep. A mailbox that is mid-drain keeps its entry: removing it would
     * orphan the latch and the work that a concurrent submit just appended.
     */
    void pruneIdleMailboxes() {
        for (Map.Entry<UUID, Mailbox> entry : mailboxes.entrySet()) {
            Mailbox mailbox = entry.getValue();
            if (mailbox.pending.isEmpty() && !mailbox.scheduled.get()) {
                mailboxes.remove(entry.getKey(), mailbox);
            }
        }
    }

    /** Releases everything for a player; call on disconnect. */
    public void forget(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Mailbox mailbox = mailboxes.remove(playerId);
        if (mailbox != null) {
            mailbox.pending.clear();
        }
    }

    private void drain(Player player) {
        Mailbox mailbox = mailboxes.get(player.getUniqueId());
        if (mailbox == null) {
            return;
        }
        drains.incrementAndGet();
        mailbox.scheduled.set(false);

        Runnable work;
        while ((work = mailbox.pending.poll()) != null) {
            try {
                work.run();
                executed.incrementAndGet();
            } catch (RuntimeException failure) {
                // Isolate: a broken listener must not strand the remaining batch.
                failures.incrementAndGet();
                LOGGER.log(Level.WARNING,
                    "UAS per-player task failed for " + player.getName() + "; continuing the batch",
                    failure);
            }
        }

        // Work can arrive while the batch was executing. The submit side saw
        // `scheduled == true` and did not arm a drain, so it is this drain's job to
        // re-arm -- otherwise those tasks would sit in the mailbox forever.
        if (!mailbox.pending.isEmpty() && mailbox.scheduled.compareAndSet(false, true)) {
            if (!scheduler.runForPlayer(player, () -> drain(player))) {
                mailbox.scheduled.set(false);
                mailbox.pending.clear();
                rejected.incrementAndGet();
            }
        }
    }

    /** Mailboxes currently tracked. With the bound and the prune, this is not a leak. */
    public int trackedPlayers() {
        return mailboxes.size();
    }

    public int maxTrackedPlayers() {
        return maxTrackedPlayers;
    }

    public long submitted() {
        return submitted.get();
    }

    /** Tasks that rode along with an already-armed drain instead of being scheduled. */
    public long coalesced() {
        return coalesced.get();
    }

    public long executed() {
        return executed.get();
    }

    public long drains() {
        return drains.get();
    }

    public long failures() {
        return failures.get();
    }

    public long rejected() {
        return rejected.get();
    }

    /** Scheduled drains avoided per submitted task; the coalescing ratio, for diagnostics. */
    public double coalescingRatio() {
        long total = submitted.get();
        return total == 0 ? 0d : (double) coalesced.get() / (double) total;
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("workQueue.trackedPlayers", Integer.toString(trackedPlayers()));
        report.put("workQueue.submitted", Long.toString(submitted.get()));
        report.put("workQueue.coalesced", Long.toString(coalesced.get()));
        report.put("workQueue.drains", Long.toString(drains.get()));
        report.put("workQueue.executed", Long.toString(executed.get()));
        report.put("workQueue.failures", Long.toString(failures.get()));
        report.put("workQueue.rejected", Long.toString(rejected.get()));
        report.put("workQueue.coalescingRatio",
            String.format(java.util.Locale.ROOT, "%.3f", coalescingRatio()));
        return report;
    }

    private static final class Mailbox {
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
    }
}
