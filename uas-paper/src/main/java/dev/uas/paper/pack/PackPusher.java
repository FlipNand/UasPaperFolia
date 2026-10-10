package dev.uas.paper.pack;

import dev.uas.api.pack.PackSource;
import dev.uas.core.UasEngine;
import dev.uas.paper.platform.PlayerWorkQueue;
import dev.uas.paper.platform.UasScheduler;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Hands players the URL of the pack the engine is streaming, and re-hands it when the
 * pack changes.
 *
 * <p>Two things make this more than a {@code setResourcePack} call.
 *
 * <h2>1. The URL points at the game port</h2>
 *
 * <p>{@code http://host:25565/uas/pack/{id}} is served by the multiplexer attached to
 * the Minecraft listener, so there is no second port to open, no separate web server to
 * supervise, and no way for the pack URL to drift out of sync with the pack content --
 * both live in the same process and the same registry.
 *
 * <h2>2. The push is driven by a generation counter, not by remembering to call it</h2>
 *
 * <p>A pack is re-published whenever a block, sound or UI changes, and the server has no
 * way to know which players saw the old one. Rather than diff content, the registry
 * exposes a monotonic {@code generation}; each player records the generation they were
 * last handed, so a sweep is a cheap comparison per player and a recompile re-pushes
 * exactly the players who are behind. This is also why the HKDF-style content hash in
 * the pack id matters: a rotated pack gets a new id and a new URL, so a client's
 * immutable cache can never serve stale assets under a re-used URL.
 *
 * <h2>3. It scales with regions, not with players</h2>
 *
 * <p>Every push goes through the per-player work queue, which means: one scheduled task
 * per player per tick however many updates arrived, executed on that player's owning
 * thread. On Folia that is a region thread, so 500 players spread over 40 regions push
 * on 40 threads concurrently. On Paper it is the main thread, which is why the coalescing
 * matters there too -- the common case of "pack changed, tell everyone" becomes one task
 * per tick per player rather than one per event.
 */
public final class PackPusher {

    private static final Logger LOGGER = Logger.getLogger(PackPusher.class.getName());

    private final UasEngine engine;
    private final UasScheduler scheduler;
    private final PlayerWorkQueue workQueue;
    private final String publicBaseUrl;
    private final String prompt;
    private final boolean required;

    /** Generation each player was last handed, keyed by player id. */
    private final Map<UUID, Long> pushedGeneration = new ConcurrentHashMap<>();
    private final AtomicLong pushes = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public PackPusher(
        UasEngine engine,
        UasScheduler scheduler,
        PlayerWorkQueue workQueue,
        String publicBaseUrl,
        String prompt,
        boolean required) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
        this.publicBaseUrl = normaliseBaseUrl(publicBaseUrl);
        this.prompt = prompt == null ? "UAS resource pack" : prompt;
        this.required = required;
    }

    /**
     * The base URL must end with the HTTP path prefix so the pack id can simply be
     * appended. Getting this wrong produces a 404 the client reports as a pack download
     * failure with no useful detail, so it is validated once, here, instead.
     */
    private static String normaliseBaseUrl(String value) {
        Objects.requireNonNull(value, "publicBaseUrl");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("publicBaseUrl must not be blank");
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException(
                "publicBaseUrl must be an absolute http(s) URL, got '" + value + "'");
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /** The URL a client would fetch a given pack from. */
    public String urlFor(PackSource source) {
        return publicBaseUrl + source.id();
    }

    /**
     * The pack currently designated as the primary push. The engine may host several
     * (for instance one per platform), but a client downloads exactly one, so the
     * choice is explicit rather than "whichever the map iterates first".
     */
    public java.util.Optional<PackSource> primaryPack() {
        return engine.packs().knownPackIds().stream()
            .sorted()
            .findFirst()
            .flatMap(engine.packs()::resolve);
    }

    /**
     * Pushes the current primary pack to a player if they are behind.
     *
     * @return true when a push was queued
     */
    public boolean pushIfStale(Player player) {
        Objects.requireNonNull(player, "player");
        long generation = engine.packs().generation();
        Long last = pushedGeneration.get(player.getUniqueId());
        if (last != null && last == generation) {
            skipped.incrementAndGet();
            return false;
        }
        return push(player, generation);
    }

    /** Forces a push regardless of whether the player has seen this generation. */
    public boolean pushNow(Player player) {
        return push(player, engine.packs().generation());
    }

    private boolean push(Player player, long generation) {
        java.util.Optional<PackSource> primary = primaryPack();
        if (primary.isEmpty()) {
            LOGGER.fine(() -> "no pack is registered; nothing to push to " + player.getName());
            return false;
        }
        PackSource source = primary.get();
        if (source.size() <= 0) {
            LOGGER.warning("refusing to push pack '" + source.id() + "': the archive is empty");
            return false;
        }

        String url = urlFor(source);
        byte[] hash = source.sha1Bytes();
        // The id identifies the pack to the client for replacement/removal. Deriving it
        // from the content hash means a new build replaces the old pack rather than
        // stacking a second one, and two servers hosting the same pack agree on the id.
        UUID packId = UUID.nameUUIDFromBytes(
            ("uas:" + source.id() + ":" + source.sha1()).getBytes(StandardCharsets.UTF_8));

        pushedGeneration.put(player.getUniqueId(), generation);
        pushes.incrementAndGet();
        // Queued, not sent inline: this runs on the player's owning thread, and a burst
        // of pushes collapses into one task.
        workQueue.submit(player, () -> {
            try {
                // addResourcePack (not the deprecated setResourcePack): the id-keyed form
                // lets a later build replace this exact pack instead of stacking a
                // second one, and lets the pack be removed without dropping the rest.
                player.addResourcePack(packId, url, hash, prompt, required);
                LOGGER.log(Level.FINE, () -> "pushed pack '" + source.id() + "' to "
                    + player.getName() + " from " + url);
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING,
                    "failed to push the UAS pack to " + player.getName(), failure);
            }
        });
        return true;
    }

    /**
     * Removes a previously pushed pack from one player.
     *
     * <p>Needed when a pack is unregistered: the client keeps an applied pack until it
     * is told otherwise, so leaving it applied means a player's custom blocks render
     * from textures whose pack the server no longer serves.
     */
    public boolean remove(Player player, UUID packId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packId, "packId");
        if (!player.isOnline()) {
            return false;
        }
        workQueue.submit(player, () -> player.removeResourcePack(packId));
        return true;
    }

    /** Sweeps every online player; the cheap per-player generation check makes this fine to run often. */
    public int pushStaleToAll() {
        int queued = 0;
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (pushIfStale(player)) {
                queued++;
            }
        }
        return queued;
    }

    /** Forgets what a player was handed; call on quit so the map cannot grow forever. */
    public void forget(UUID playerId) {
        pushedGeneration.remove(playerId);
    }

    /** Drops bookkeeping for players who are no longer online. */
    public int pruneOffline() {
        int removed = 0;
        for (UUID id : java.util.Set.copyOf(pushedGeneration.keySet())) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                pushedGeneration.remove(id);
                removed++;
            }
        }
        return removed;
    }

    public int trackedPlayers() {
        return pushedGeneration.size();
    }

    public long pushes() {
        return pushes.get();
    }

    public long skipped() {
        return skipped.get();
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("packPusher.baseUrl", publicBaseUrl);
        report.put("packPusher.required", Boolean.toString(required));
        report.put("packPusher.generation", Long.toString(engine.packs().generation()));
        report.put("packPusher.trackedPlayers", Integer.toString(trackedPlayers()));
        report.put("packPusher.pushes", Long.toString(pushes.get()));
        report.put("packPusher.skipped", Long.toString(skipped.get()));
        report.put("packPusher.scheduling", scheduler.platform().isRegionised()
            ? "per-player region threads" : "main thread");
        return report;
    }
}
