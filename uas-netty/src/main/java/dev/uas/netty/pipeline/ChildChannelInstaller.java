package dev.uas.netty.pipeline;

import io.netty.channel.Channel;

/**
 * Installs handlers into a freshly accepted client channel.
 *
 * <p>Called from the server channel's read path, <em>before</em> Netty's
 * {@code ServerBootstrapAcceptor} hands the channel to the bootstrap's child
 * handler. That timing is the whole trick: the child pipeline is still empty, so
 * {@code addFirst} is guaranteed to put the sniffer ahead of everything the server
 * later appends (the splitter, the decoder, the timeout handler), and of anything
 * added by the child initializer afterwards.
 *
 * <p>Implementations must never throw: an exception here would break the accept
 * path and take connections down with it.
 */
@FunctionalInterface
public interface ChildChannelInstaller {

    void install(Channel child);
}
