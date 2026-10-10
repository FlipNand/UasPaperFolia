package dev.uas.paper;

import dev.uas.core.memory.MemoryProfile;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Everything an operator can tune, in one immutable record.
 *
 * <p>A record rather than a live {@code FileConfiguration} handle, for a reason that
 * matters at runtime: the engine reads these values from Netty event loops, region
 * threads and async tasks. Snapshotting them once at startup means there is no shared
 * mutable config object to read unsynchronised, and a reload builds a whole new engine
 * rather than mutating one that is in use.
 *
 * <p>Defaults are chosen for the constrained case, not the comfortable one. A server
 * operator who does nothing at all gets the memory-safe behaviour and a working pack
 * URL; the tuning knobs only ever make it faster on big hardware.
 */
public record UasPaperConfig(
    boolean lowMemoryMode,
    String publicBaseUrl,
    String packPrompt,
    boolean packRequired,
    boolean verifyPacksOnRegister,
    int maxTrackedPlayers,
    int hookRetryIntervalTicks,
    int maxHookAttempts,
    int packSweepIntervalTicks,
    boolean ownershipGuard,
    boolean logStartupTrace) {

    /** Path prefix the multiplexer serves packs under; must match {@code UasHttpConfig}. */
    public static final String DEFAULT_PATH_PREFIX = "/uas/pack/";

    public UasPaperConfig {
        Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        packPrompt = packPrompt == null ? "UAS resource pack" : packPrompt;
        if (maxTrackedPlayers <= 0) {
            throw new IllegalArgumentException("maxTrackedPlayers must be > 0");
        }
        if (hookRetryIntervalTicks <= 0) {
            throw new IllegalArgumentException("hookRetryIntervalTicks must be > 0");
        }
        if (maxHookAttempts <= 0) {
            throw new IllegalArgumentException("maxHookAttempts must be > 0");
        }
        if (packSweepIntervalTicks <= 0) {
            throw new IllegalArgumentException("packSweepIntervalTicks must be > 0");
        }
    }

    /**
     * The default configuration.
     *
     * <p>{@code publicBaseUrl} defaults to loopback with the given port because the
     * server cannot discover its own external address: behind NAT, a proxy or a container
     * the bind address and the address a client must dial are different, and guessing
     * produces a pack URL that silently 404s from outside. Loopback is the honest default
     * -- it is correct for a local test and obviously wrong for production, which is
     * exactly what you want a default to be.
     */
    public static UasPaperConfig defaults(int port) {
        return new UasPaperConfig(
            false,
            "http://127.0.0.1:" + port + DEFAULT_PATH_PREFIX,
            "UAS resource pack",
            false,
            false,
            4096,
            10,
            30,
            200,
            true,
            true);
    }

    /** Reads config, falling back to {@link #defaults} for anything absent. */
    public static UasPaperConfig from(ConfigurationSection section, int port) {
        UasPaperConfig base = defaults(port);
        if (section == null) {
            return base;
        }
        return new UasPaperConfig(
            section.getBoolean("low-memory-mode", base.lowMemoryMode()),
            section.getString("public-base-url", base.publicBaseUrl()),
            section.getString("pack-prompt", base.packPrompt()),
            section.getBoolean("pack-required", base.packRequired()),
            section.getBoolean("verify-packs-on-register", base.verifyPacksOnRegister()),
            section.getInt("max-tracked-players", base.maxTrackedPlayers()),
            section.getInt("hook-retry-interval-ticks", base.hookRetryIntervalTicks()),
            section.getInt("max-hook-attempts", base.maxHookAttempts()),
            section.getInt("pack-sweep-interval-ticks", base.packSweepIntervalTicks()),
            section.getBoolean("ownership-guard", base.ownershipGuard()),
            section.getBoolean("log-startup-trace", base.logStartupTrace()));
    }

    /**
     * A warning when {@link #publicBaseUrl()} names a port the server is not listening on.
     *
     * <p>The host in that URL cannot be checked -- behind NAT, a proxy or a container it is
     * deliberately not the bind address -- but the port can, and a mismatch there is always
     * a misconfiguration. It is worth saying out loud because the failure is otherwise
     * silent: the pack compiles, registers and serves correctly, and the only symptom is
     * that every client is handed a URL nothing answers.
     *
     * @param serverPort the port the server actually bound
     * @return the warning text, or empty when the URL names no port, is unparseable, or
     *         agrees with {@code serverPort}
     */
    public Optional<String> portMismatchWarning(int serverPort) {
        int advertised = advertisedPort();
        if (advertised <= 0 || advertised == serverPort) {
            return Optional.empty();
        }
        return Optional.of("UAS public-base-url '" + publicBaseUrl + "' names port "
            + advertised + " but the server is listening on " + serverPort
            + "; players would be handed an unreachable pack URL. Set public-base-url to port "
            + serverPort + ", or to the public port if the server is behind a proxy.");
    }

    /**
     * The port explicitly named in {@link #publicBaseUrl()}, or {@code -1} when it names
     * none (in which case the URL is not evidence of anything and must not be warned about).
     */
    public int advertisedPort() {
        try {
            return new java.net.URI(publicBaseUrl.trim()).getPort();
        } catch (java.net.URISyntaxException notAUrl) {
            return -1;
        }
    }

    /**
     * The memory profile for this configuration.
     *
     * <p>{@code low-memory-mode: true} forces constrained mode even on a large heap,
     * because an operator may be sizing for many small instances on one machine rather
     * than one large one. Left false, the profile is still auto-detected from the heap,
     * so a 4 GiB instance gets the constrained profile without being told.
     */
    public MemoryProfile memoryProfile() {
        return MemoryProfile.detect(Runtime.getRuntime().maxMemory(), lowMemoryMode);
    }

    /** The HTTP tuning that matches the memory profile. */
    public dev.uas.netty.http.UasHttpConfig httpConfig() {
        return memoryProfile().isConstrained()
            ? dev.uas.netty.http.UasHttpConfig.lowRam()
            : dev.uas.netty.http.UasHttpConfig.defaults();
    }
}
