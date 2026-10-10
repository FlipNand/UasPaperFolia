package dev.uas.netty.pipeline;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sits on the <em>server</em> channel immediately before Netty's
 * {@code ServerBootstrapAcceptor}.
 *
 * <p>{@code ServerBootstrapAcceptor.channelRead} receives the accepted child
 * {@link Channel} as its message, appends the bootstrap's child handler to the
 * child pipeline, and only then registers the child. Intercepting the same message
 * one handler earlier is therefore the only hook-point that runs before the child
 * pipeline is populated -- no {@code Unsafe}, no reflective field surgery, no
 * race with connection acceptance.
 *
 * <p>The message is always forwarded so the acceptor still does its job.
 */
public final class ChildChannelInterceptor extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = Logger.getLogger(ChildChannelInterceptor.class.getName());

    private final ChildChannelInstaller installer;

    public ChildChannelInterceptor(ChildChannelInstaller installer) {
        this.installer = installer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Channel child) {
            try {
                installer.install(child);
            } catch (RuntimeException e) {
                // A failure to attach our handler must degrade to a plain vanilla
                // connection, never to a refused or half-initialised one.
                LOGGER.log(Level.WARNING,
                    "UAS could not attach the asset multiplexer to a new connection; "
                        + "that connection will run in vanilla mode", e);
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.log(Level.FINE, "UAS child-channel interceptor saw an exception", cause);
        ctx.fireExceptionCaught(cause);
    }
}
