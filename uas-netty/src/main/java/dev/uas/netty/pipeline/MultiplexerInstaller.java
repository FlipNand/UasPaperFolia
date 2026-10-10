package dev.uas.netty.pipeline;

import dev.uas.netty.http.HttpProtocolSniffer;
import dev.uas.netty.http.PackHttpRouter;
import dev.uas.netty.http.SniffStats;
import dev.uas.netty.http.UasHttpConfig;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attaches the protocol sniffer to every accepted connection.
 *
 * <p>A fresh {@link HttpProtocolSniffer} per channel is required: the sniffer is
 * stateful (it accumulates leading bytes and remembers its decision), so it must
 * not be {@code @Sharable}. This installer is the sharable part -- it holds only
 * the configuration and the shared counters.
 */
public final class MultiplexerInstaller implements ChildChannelInstaller {

    private static final Logger LOGGER = Logger.getLogger(MultiplexerInstaller.class.getName());

    private final UasHttpConfig config;
    private final PackHttpRouter router;
    private final SniffStats stats = new SniffStats();

    public MultiplexerInstaller(UasHttpConfig config, PackHttpRouter router) {
        this.config = Objects.requireNonNull(config, "config");
        this.router = Objects.requireNonNull(router, "router");
    }

    public UasHttpConfig config() {
        return config;
    }

    public PackHttpRouter router() {
        return router;
    }

    public SniffStats stats() {
        return stats;
    }

    @Override
    public void install(Channel child) {
        if (child.pipeline().get(PipelineNames.MULTIPLEXER) != null) {
            return;
        }
        child.pipeline().addFirst(
            PipelineNames.MULTIPLEXER, new HttpProtocolSniffer(config, router, stats));
        LOGGER.log(Level.FINEST, () -> "UAS attached the asset multiplexer to " + child);
    }

    /** True when this channel already carries the multiplexer. */
    public static boolean isAttached(Channel child) {
        return child.pipeline().get(PipelineNames.MULTIPLEXER) != null;
    }
}
