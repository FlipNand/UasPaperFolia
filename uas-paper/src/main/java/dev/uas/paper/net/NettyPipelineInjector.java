package dev.uas.paper.net;

import dev.uas.netty.pipeline.MultiplexerInstaller;
import dev.uas.netty.pipeline.ServerChannelHook;
import io.netty.channel.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Attaches the asset multiplexer to the server's own listening socket.
 *
 * <p>This is the whole of the "no second port" requirement. Rather than bind an HTTP
 * listener -- which would mean a firewall change, a second port in the hosting panel,
 * and a URL the operator has to configure correctly -- the engine reaches into the
 * socket the server already accepts connections on and puts its sniffer in front of the
 * vanilla frame decoders. A client that speaks HTTP gets the pack; a client that speaks
 * Minecraft is untouched, byte for byte.
 *
 * <p>The hook itself ({@link ServerChannelHook}) inserts immediately before Netty's
 * {@code ServerBootstrapAcceptor}, which is the only point that runs before the child
 * pipeline is populated. That timing, not any reflective field access, is what makes the
 * injection reliable across server builds.
 *
 * <h2>Why this is retried rather than done once</h2>
 *
 * <p>A plugin's {@code onEnable} can run before the server has finished binding, and
 * Paper installs its acceptor as part of a task posted to the event loop. So injection
 * is expressed as "try now, and if the socket is not there yet, try again" -- driven by
 * the caller's scheduler rather than a thread of our own, because a plugin must never
 * own a thread that outlives the server's lifecycle.
 */
public final class NettyPipelineInjector {

    private final MultiplexerInstaller installer;
    private final Duration hookTimeout;

    /** Channels this injector has successfully hooked; used to unhook precisely. */
    private final List<Channel> hooked = java.util.Collections.synchronizedList(new ArrayList<>());

    public NettyPipelineInjector(MultiplexerInstaller installer, Duration hookTimeout) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.hookTimeout = Objects.requireNonNull(hookTimeout, "hookTimeout");
    }

    /** What one injection attempt did, in a form the startup log can print verbatim. */
    public record Report(int channelsFound, int channelsHooked, List<String> trace) {

        public Report {
            trace = List.copyOf(trace);
        }

        public boolean attached() {
            return channelsHooked > 0;
        }

        /** True when the sockets were not there yet; the caller should retry. */
        public boolean shouldRetry() {
            return channelsFound == 0;
        }

        public String summarise() {
            return channelsHooked + " of " + channelsFound + " listening channel(s) hooked";
        }

        public String describeTrace() {
            return String.join(System.lineSeparator(), trace);
        }
    }

    /**
     * One injection attempt across every listening channel.
     *
     * <p>A channel that is already hooked is counted as hooked rather than re-hooked:
     * {@link ServerChannelHook} treats an existing interceptor as success, so a retry
     * after a partial failure cannot stack two interceptors on the same socket.
     */
    public Report install() {
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locate();
        List<String> trace = new ArrayList<>(discovery.trace());
        int hookedCount = 0;

        for (Channel channel : discovery.channels()) {
            if (ServerChannelHook.isInstalled(channel)) {
                trace.add("already hooked: " + channelName(channel));
                rememberHooked(channel);
                hookedCount++;
                continue;
            }
            boolean installed = ServerChannelHook.install(channel, installer, hookTimeout);
            if (installed) {
                trace.add("hooked: " + channelName(channel));
                rememberHooked(channel);
                hookedCount++;
            } else {
                trace.add("could NOT hook: " + channelName(channel)
                    + " (no ServerBootstrapAcceptor appeared within " + hookTimeout + ")");
            }
        }
        return new Report(discovery.channels().size(), hookedCount, trace);
    }

    /** Detaches the hook from every channel this injector attached it to. */
    public int uninstall() {
        int removed = 0;
        synchronized (hooked) {
            for (Channel channel : hooked) {
                if (ServerChannelHook.uninstall(channel)) {
                    removed++;
                }
            }
            hooked.clear();
        }
        return removed;
    }

    /** Channels currently carrying the interceptor. */
    public int hookedChannelCount() {
        synchronized (hooked) {
            return hooked.size();
        }
    }

    /** True when every listening channel the server exposes carries the interceptor. */
    public boolean isFullyHooked() {
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locate();
        if (discovery.isEmpty()) {
            return false;
        }
        for (Channel channel : discovery.channels()) {
            if (!ServerChannelHook.isInstalled(channel)) {
                return false;
            }
        }
        return true;
    }

    /** The multiplexer being installed, so callers can read its sniffer counters. */
    public MultiplexerInstaller installer() {
        return installer;
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("net.hookedChannels", Integer.toString(hookedChannelCount()));
        report.put("net.hookTimeout", hookTimeout.toString());
        report.put("net.portlessDelivery", "true");
        return report;
    }

    private void rememberHooked(Channel channel) {
        synchronized (hooked) {
            if (!hooked.contains(channel)) {
                hooked.add(channel);
            }
        }
    }

    private static String channelName(Channel channel) {
        return channel.getClass().getSimpleName() + "[" + channel.localAddress() + "]";
    }
}
