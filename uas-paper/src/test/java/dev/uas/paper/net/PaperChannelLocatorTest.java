package dev.uas.paper.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The walk over server internals is the one piece of the injection path that can break
 * on a server upgrade without any compile error, so it is tested against fixtures that
 * imitate each accessor shape it claims to support.
 *
 * <p>The fixtures are public because reflection can only invoke public methods on public
 * classes; a package-private fixture would fail the access check and the test would pass
 * or fail for the wrong reason.
 */
public class PaperChannelLocatorTest {

    /** The shape Paper uses today: server -> connection -> List&lt;ChannelFuture&gt; -> channel(). */
    public static final class ServerWithFutureList {
        private final List<Object> connections;

        public ServerWithFutureList(Channel channel) {
            this.connections = List.of(new FutureOf(channel));
        }

        public Object getConnection() {
            return new ListenerWith(connections);
        }
    }

    /** The older accessor name, and a bare Channel rather than a future. */
    public static final class ServerWithBareChannels {
        private final Channel channel;

        public ServerWithBareChannels(Channel channel) {
            this.channel = channel;
        }

        public Object getServerConnection() {
            return new ListenerWith(List.of(channel));
        }
    }

    /** An array container, which one of the historical shapes used. */
    public static final class ServerWithArray {
        private final Channel channel;

        public ServerWithArray(Channel channel) {
            this.channel = channel;
        }

        public Object getConnection() {
            return new ArrayListener(new Object[] {new FutureOf(channel)});
        }
    }

    /** A server that is itself the connection listener (no intermediate hop). */
    public static final class SelfServingServer {
        private final Channel channel;

        public SelfServingServer(Channel channel) {
            this.channel = channel;
        }

        public List<Object> getConnections() {
            return List.of(new FutureOf(channel));
        }
    }

    /** Nothing matches, so the walk must give up with an explanation. */
    public static final class OpaqueObject {
        public String somethingElse() {
            return "no";
        }
    }

    /** Fixture: a connection listener returning a fixed container. */
    public static final class ListenerWith {
        private final Object container;

        public ListenerWith(Object container) {
            this.container = container;
        }

        public Object getConnections() {
            return container;
        }
    }

    /** Fixture: an array-shaped container. */
    public static final class ArrayListener {
        private final Object[] channels;

        public ArrayListener(Object[] channels) {
            this.channels = channels;
        }

        public Object[] getListeners() {
            return channels;
        }
    }

    /** Fixture: a channel future. */
    public static final class FutureOf {
        private final Channel channel;

        public FutureOf(Channel channel) {
            this.channel = channel;
        }

        public Channel channel() {
            return channel;
        }
    }

    private static Channel newChannel() {
        return new EmbeddedChannel();
    }

    @Test
    void findsAChannelBehindAChannelFuture() {
        Channel channel = newChannel();
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithFutureList(channel));

        assertEquals(List.of(channel), discovery.channels());
        assertTrue(discovery.trace().stream().anyMatch(line -> line.contains("listening channel")));
    }

    @Test
    void findsABareChannelWithTheOlderAccessorName() {
        Channel channel = newChannel();
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithBareChannels(channel));

        assertEquals(List.of(channel), discovery.channels(),
            "getServerConnection()/getConnections() returning channels directly must work");
    }

    @Test
    void findsAChannelInsideAnArrayContainer() {
        Channel channel = newChannel();
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithArray(channel));

        assertEquals(List.of(channel), discovery.channels(),
            "an array-shaped listener must be flattened, not ignored");
    }

    @Test
    void acceptsARootThatIsItselfTheListener() {
        Channel channel = newChannel();
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new SelfServingServer(channel));

        assertEquals(List.of(channel), discovery.channels());
    }

    @Test
    void givesUpWithAnActionableTraceRatherThanSilence() {
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locateFrom(new OpaqueObject());

        assertTrue(discovery.isEmpty());
        assertFalse(discovery.trace().isEmpty(), "a failed walk must explain itself");
        String trace = discovery.describeTrace();
        assertTrue(trace.contains("OpaqueObject"), trace);
        assertTrue(trace.contains("no connection listener found")
                || trace.contains("exposed no bound channels"),
            "the trace must name the hop that failed: " + trace);
    }

    @Test
    void theTraceRecordsEveryHopItTook() {
        Channel channel = newChannel();
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithFutureList(channel));
        String trace = discovery.describeTrace();

        assertTrue(trace.contains("ServerWithFutureList"), trace);
        assertTrue(trace.contains("connection listener: ListenerWith"), trace);
        assertTrue(trace.contains("located 1 listening channel"), trace);
    }

    @Test
    void anElementWithoutAChannelAccessorIsSkippedAndReported() {
        Channel channel = newChannel();
        // One future and one unrelated object: the unrelated one must not abort the walk.
        ServerWithList server = new ServerWithList(List.of(new FutureOf(channel), "not a future"));
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locateFrom(server);

        assertEquals(List.of(channel), discovery.channels());
        assertTrue(discovery.describeTrace().contains("ignored element of type String"),
            "skipping an element must be visible: " + discovery.describeTrace());
    }

    /** Fixture: a listener over an arbitrary list. */
    public static final class ServerWithList {
        private final List<Object> connections;

        public ServerWithList(List<Object> connections) {
            this.connections = connections;
        }

        public Object getConnection() {
            return new ListenerWith(connections);
        }
    }

    /**
     * Paper 26.3's real shape, reproduced from a live server: the listening
     * {@code ChannelFuture}s live in a private typed field named {@code channels} with no
     * accessor at all, while the one accessor that does exist returns connected logins.
     */
    public static final class MoonriseListener {
        private final List<ChannelFuture> channels = new ArrayList<>();
        private final List<Object> connections = new ArrayList<>();

        public void addSocket(ChannelFuture future) {
            channels.add(future);
        }

        public void addLogin(Object connection) {
            connections.add(connection);
        }

        public List<Object> getConnections() {
            return connections;
        }
    }

    /**
     * Mimics {@code net.minecraft.network.Connection}: the channel is a public
     * <em>field</em>, so a walk that accepted fields as yielders would report a player's
     * socket as a listening channel.
     */
    public static final class LoginWithPublicChannelField {
        public final Channel channel;

        public LoginWithPublicChannelField(Channel channel) {
            this.channel = channel;
        }
    }

    @Test
    void findsTheTypedSocketFieldOnAPaper2623ShapedListener() {
        Channel socket = newChannel();
        MoonriseListener listener = new MoonriseListener();
        // A genuinely ChannelFuture-typed holder, as on the real listener, so the walk has
        // to unwrap it through channel() exactly as it does in production.
        ChannelFuture future = socket.newSucceededFuture();
        listener.addSocket(future);
        // A login is present too: the decoy accessor returns something, and it is not the socket.
        listener.addLogin(new LoginWithPublicChannelField(newChannel()));

        ServerWithListener server = new ServerWithListener(listener);
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locateFrom(server);

        assertEquals(List.of(socket), discovery.channels(),
            "the private channel-typed field must win over getConnections()");
        assertTrue(discovery.describeTrace().contains("field channels"),
            "the trace must name the holder it used: " + discovery.describeTrace());
    }

    @Test
    void neverMistakesAPlayerChannelForAListeningChannel() {
        // Only the decoy accessor exists, and its elements expose the channel as a field.
        MoonriseListener listener = new MoonriseListener();
        Channel playerSocket = newChannel();
        listener.addLogin(new LoginWithPublicChannelField(playerSocket));

        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithListener(listener));

        assertTrue(discovery.isEmpty(),
            "a login's channel field is not a listening channel, got " + discovery.channels());
        assertTrue(discovery.describeTrace().contains("ignored element of type LoginWithPublicChannelField"),
            "the rejected element must be reported: " + discovery.describeTrace());
    }

    @Test
    void anEmptyChannelTypedHolderIsReportedAsNotYetBound() {
        // What a plugin sees when it enables before the server has bound its socket: the
        // holder is right, it is simply empty. The trace has to say so, because the caller
        // has to distinguish this (retry) from a walk that no longer matches the server.
        PaperChannelLocator.Discovery discovery =
            PaperChannelLocator.locateFrom(new ServerWithListener(new MoonriseListener()));

        assertTrue(discovery.isEmpty());
        String trace = discovery.describeTrace();
        assertTrue(trace.contains("field channels") && trace.contains("channel-typed but empty so far"),
            "an unbound listener must be named as such rather than reported as a broken walk: " + trace);
    }

    /** Fixture: a server whose listener is supplied directly. */
    public static final class ServerWithListener {
        private final Object listener;

        public ServerWithListener(Object listener) {
            this.listener = listener;
        }

        public Object getConnection() {
            return listener;
        }
    }

    @Test
    void theLiveEntryPointIsSafeToCallWithoutAServer() {
        // In this JVM there is no server bound and no NMS, so locate() must not throw;
        // it must return an empty discovery with a trace. That is exactly the situation
        // a retry loop has to handle.
        PaperChannelLocator.Discovery discovery = PaperChannelLocator.locate();
        assertFalse(discovery.trace().isEmpty());
    }
}
