package dev.uas.netty.pipeline;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attaches a {@link ChildChannelInterceptor} to a listening channel.
 *
 * <p>Netty installs its acceptor as an anonymous handler inside
 * {@code ServerBootstrap.init}, and on modern Paper that happens inside a task
 * posted to the event loop -- so it may not be present the instant {@code bind()}
 * completes. Rather than guess, this hook polls for the acceptor by class name and
 * inserts immediately before it.
 *
 * <p>Locating the acceptor by class name (instead of by reflective field access)
 * keeps the hook working across Netty minor versions and avoids
 * {@code --add-opens} requirements, which a shaded server plugin cannot rely on.
 */
public final class ServerChannelHook {

    private static final Logger LOGGER = Logger.getLogger(ServerChannelHook.class.getName());

    /** Netty's acceptor class name; matched textually so no internal type is referenced. */
    public static final String ACCEPTOR_CLASS_NAME = "io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor";

    private static final long POLL_INTERVAL_MILLIS = 5;

    private ServerChannelHook() {
    }

    /**
     * Inserts the interceptor, waiting for the acceptor to appear.
     *
     * @return true when the interceptor is in place
     */
    public static boolean install(Channel serverChannel, ChildChannelInstaller installer, Duration timeout) {
        if (!(serverChannel instanceof ServerChannel)) {
            throw new IllegalArgumentException(
                "UAS pipeline hook needs a ServerChannel, got " + serverChannel.getClass().getName());
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (installOnce(serverChannel, installer)) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);

        LOGGER.warning("UAS could not find " + ACCEPTOR_CLASS_NAME + " on "
            + serverChannel + " within " + timeout
            + "; asset streaming over the Minecraft port is unavailable on this connection");
        return false;
    }

    /** One non-blocking attempt. */
    public static boolean installOnce(Channel serverChannel, ChildChannelInstaller installer) {
        ChannelPipeline pipeline = serverChannel.pipeline();
        if (pipeline.get(PipelineNames.CHILD_HOOK) != null) {
            // Already attached (possibly to a previous installer); treat as success so
            // a re-entrant call cannot stack interceptors.
            return true;
        }
        Optional<String> acceptor = findAcceptorName(pipeline);
        if (acceptor.isEmpty()) {
            return false;
        }
        pipeline.addBefore(acceptor.get(), PipelineNames.CHILD_HOOK, new ChildChannelInterceptor(installer));
        LOGGER.fine(() -> "UAS attached its child-channel hook before '" + acceptor.get() + "' on "
            + serverChannel);
        return true;
    }

    /** True when the interceptor is already present. */
    public static boolean isInstalled(Channel serverChannel) {
        return serverChannel.pipeline().get(PipelineNames.CHILD_HOOK) != null;
    }

    /** Removes the interceptor; new connections then run in pure vanilla mode. */
    public static boolean uninstall(Channel serverChannel) {
        ChannelHandler existing = serverChannel.pipeline().get(PipelineNames.CHILD_HOOK);
        if (existing == null) {
            return false;
        }
        serverChannel.pipeline().remove(existing);
        return true;
    }

    static Optional<String> findAcceptorName(ChannelPipeline pipeline) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (ACCEPTOR_CLASS_NAME.equals(entry.getValue().getClass().getName())) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Diagnostic description of a listening channel's pipeline. */
    public static String describe(Channel serverChannel) {
        StringBuilder sb = new StringBuilder("pipeline[").append(serverChannel.getClass().getSimpleName()).append("]{");
        boolean first = true;
        for (Map.Entry<String, ChannelHandler> entry : serverChannel.pipeline().toMap().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue().getClass().getSimpleName());
        }
        return sb.append('}').toString();
    }
}
