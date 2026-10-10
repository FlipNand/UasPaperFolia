package dev.uas.paper.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The coalescing guarantee is the reason this class exists, so it is asserted directly:
 * a burst of N updates for one player must produce exactly one scheduled task, and all N
 * must still run.
 */
class PlayerWorkQueueTest {

    /** A player stand-in: {@code Player} is an interface, so a proxy is a real instance. */
    private static Player fakePlayer(UUID id, boolean online) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> online;
                case "getName" -> "fake-" + id;
                case "toString" -> "FakePlayer[" + id + "]";
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }

    /** A scheduler that queues player tasks instead of running them, so tests can step ticks. */
    private static final class ManualScheduler implements UasScheduler {

        private final Deque<Runnable> playerTasks = new ArrayDeque<>();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private boolean accept = true;

        @Override
        public ServerPlatform platform() {
            return ServerPlatform.FOLIA;
        }

        @Override
        public boolean runForPlayer(Player player, Runnable work) {
            if (!accept) {
                rejected.incrementAndGet();
                return false;
            }
            scheduled.incrementAndGet();
            playerTasks.add(work);
            return true;
        }

        @Override
        public boolean runAt(Location location, Runnable work) {
            work.run();
            return true;
        }

        @Override
        public boolean runGlobal(Runnable work) {
            work.run();
            return true;
        }

        @Override
        public boolean runGlobalDelayed(Runnable work, long delayTicks) {
            return true;
        }

        @Override
        public boolean runAsync(Runnable work) {
            work.run();
            return true;
        }

        @Override
        public boolean ownsCurrentThread(Player player) {
            return false;
        }

        @Override
        public Map<String, String> describe() {
            Map<String, String> report = new LinkedHashMap<>();
            report.put("platform", platform().displayName());
            return report;
        }

        /** Runs every queued task, as the owning thread would. */
        void tick() {
            while (!playerTasks.isEmpty()) {
                playerTasks.poll().run();
            }
        }

        int scheduledCount() {
            return scheduled.get();
        }

        void stopAccepting() {
            accept = false;
        }
    }

    @Test
    void aBurstForOnePlayerBecomesOneScheduledTask() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player player = fakePlayer(UUID.randomUUID(), true);
        List<Integer> order = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            int value = i;
            queue.submit(player, () -> order.add(value));
        }

        assertTrue(scheduler.scheduledCount() == 1,
            "50 updates must arm exactly one drain, but " + scheduler.scheduledCount() + " were scheduled");
        assertEquals(0, order.size(), "nothing runs until the owning thread drains");
        assertEquals(49, queue.coalesced());

        scheduler.tick();
        assertEquals(50, order.size());
        assertEquals(50, queue.executed());
        // Ordering is part of the contract: a late state update must not overtake an early one.
        for (int i = 0; i < 50; i++) {
            assertEquals(i, order.get(i));
        }
    }

    @Test
    void differentPlayersGetTheirOwnDrain() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player a = fakePlayer(UUID.randomUUID(), true);
        Player b = fakePlayer(UUID.randomUUID(), true);

        queue.submit(a, () -> { });
        queue.submit(b, () -> { });
        queue.submit(a, () -> { });

        assertEquals(2, scheduler.scheduledCount(), "one drain per player, not per task or per server");
        assertEquals(1, queue.coalesced());
        scheduler.tick();
        assertEquals(3, queue.executed());
    }

    @Test
    void workSubmittedDuringADrainIsExecutedRatherThanStranded() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player player = fakePlayer(UUID.randomUUID(), true);

        List<String> order = new ArrayList<>();
        queue.submit(player, () -> {
            order.add("first");
            // Arrives while the batch is draining: the submit side sees `scheduled == false`
            // only after the latch is released, so this rides along with the re-armed drain.
            queue.submit(player, () -> order.add("re-entrant"));
        });

        scheduler.tick();
        assertTrue(order.contains("first"));
        assertTrue(order.contains("re-entrant"),
            "a task queued during a drain must not be lost, got " + order);
        assertEquals(2, queue.executed());
    }

    @Test
    void anOfflinePlayerIsRejectedRatherThanRetained() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player offline = fakePlayer(UUID.randomUUID(), false);

        queue.submit(offline, () -> { });

        assertEquals(0, scheduler.scheduledCount());
        assertEquals(1, queue.rejected());
        assertEquals(0, queue.trackedPlayers(), "an offline player must not create a mailbox");
    }

    @Test
    void aRejectedSchedulingAttemptDropsTheBatchRatherThanLeavingAStuckLatch() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player player = fakePlayer(UUID.randomUUID(), true);

        scheduler.stopAccepting();
        queue.submit(player, () -> { });

        assertEquals(1, queue.rejected());
        // The latch must be released: if it were left armed, every later task for this
        // player would coalesce into a drain that never runs, and the player would
        // silently stop receiving updates for the rest of their session.
        scheduler.accept = true;
        queue.submit(player, () -> { });
        assertEquals(1, scheduler.scheduledCount());
        scheduler.tick();
        assertEquals(1, queue.executed());
    }

    @Test
    void aFailingTaskDoesNotStrandTheRestOfTheBatch() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player player = fakePlayer(UUID.randomUUID(), true);
        AtomicInteger ran = new AtomicInteger();

        queue.submit(player, ran::incrementAndGet);
        queue.submit(player, () -> {
            throw new IllegalStateException("this listener is broken");
        });
        queue.submit(player, ran::incrementAndGet);

        scheduler.tick();

        assertEquals(2, ran.get(), "both healthy tasks must run");
        assertEquals(1, queue.failures());
        assertEquals(3, queue.executed() + queue.failures());
    }

    @Test
    void forgettingAPlayerOnQuitReleasesTheirMailbox() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        UUID id = UUID.randomUUID();
        Player player = fakePlayer(id, true);

        queue.submit(player, () -> { });
        assertEquals(1, queue.trackedPlayers());

        queue.forget(id);
        assertEquals(0, queue.trackedPlayers());
        // The already-armed drain must tolerate its mailbox having been removed.
        scheduler.tick();
        assertEquals(0, queue.executed());
    }

    @Test
    void aLongLivedServerDoesNotAccumulateAMailboxPerPlayerWhoeverJoined() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler, 4);

        // 200 players join over time. Each burst is drained and the idle mailboxes are
        // swept at the ceiling, so the tracked set returns to zero instead of growing
        // with the number of distinct players the server has ever seen.
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 4; i++) {
                queue.submit(fakePlayer(UUID.randomUUID(), true), () -> { });
            }
            scheduler.tick();
        }
        queue.pruneIdleMailboxes();

        assertEquals(0, queue.trackedPlayers());
        assertTrue(queue.trackedPlayers() <= queue.maxTrackedPlayers());
        assertEquals(200, queue.executed());
    }

    @Test
    void theCoalescingRatioReportsTheSaving() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerWorkQueue queue = new PlayerWorkQueue(scheduler);
        Player player = fakePlayer(UUID.randomUUID(), true);

        for (int i = 0; i < 10; i++) {
            queue.submit(player, () -> { });
        }
        // 1 armed drain, 9 ridden along: 0.9 of the submissions were coalesced.
        assertEquals(0.9d, queue.coalescingRatio(), 1e-9);
        assertTrue(queue.describe().get("workQueue.coalescingRatio").startsWith("0.9"));
    }
}
