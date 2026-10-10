package dev.uas.paper.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the {@link Channel}s the server is listening on, by reflection.
 *
 * <p>The engine has to attach to the vanilla listening socket rather than open its own,
 * and that socket is private server state with no Bukkit-level accessor. So it is
 * reached through an explicit, ordered walk over the names the accessor has been
 * called across server generations:
 *
 * <pre>
 *   server          -- getServer() / net.minecraft.server.MinecraftServer.getServer()
 *     getConnection()  (also: getServerConnection(), getConnectionListener())
 *       channels field, or getChannels()/getListeners()/getConnections() (older builds)
 *         ChannelFuture.channel() -> the listening Channel
 * </pre>
 *
 * <p>Four decisions here are deliberate and worth stating:
 *
 * <ul>
 *   <li><strong>Holders are found by field, not only by accessor.</strong> Paper 26.3
 *       (the Moonrise networking rewrite) keeps its listening {@code ChannelFuture}s in
 *       {@code ServerConnectionListener.channels}, a <em>private field with no accessor
 *       of any kind</em>. A method-only walk can therefore never see the socket on such a
 *       build, no matter how many accessor names it tries.</li>
 *   <li><strong>The right holder is chosen by element type, not by name.</strong> The one
 *       accessor that does exist on Paper 26.3, {@code getConnections()}, returns
 *       {@code List<net.minecraft.network.Connection>} -- connected <em>logins</em>, not
 *       channels. Worse, {@code Connection} exposes a public {@code channel} field, so a
 *       naive walk that unwrapped it would happily attach the asset multiplexer to a
 *       player's socket instead of the listener. Candidates whose declared element type is
 *       a {@code Channel}/{@code ChannelFuture} are therefore preferred, and
 *       {@link #CHANNEL_YIELDERS} is resolved through methods only -- a public
 *       {@code channel} <em>field</em> is not a yielder.</li>
 *   <li><strong>Every step records a trace line.</strong> When a future server build
 *       renames something, the failure to attach must say precisely which hop broke and
 *       what it saw instead. A silent "no pools bound" is the worst possible outcome:
 *       the operator assumes asset streaming works and only finds out when a player
 *       sees no custom textures.</li>
 *   <li><strong>The walk is separated from "where the server is".</strong>
 *       {@link #locateFrom(Object)} takes an arbitrary root object, which is what makes
 *       this testable without a running server: a fixture that imitates the accessor
 *       shape proves the walk itself, independently of whether Minecraft's internals
 *       still match it.</li>
 * </ul>
 *
 * <p>Nothing here mutates server state or assumes a mapping set: it only reads. Both the
 * accessor walk and the field walk were verified against a live Paper 26.3 server, whose
 * sockets are reachable only through the field path.
 */
public final class PaperChannelLocator {

    /** Accessor names that have returned the server's connection listener. */
    static final List<String> SERVER_ACCESSORS = List.of(
        "getServer", "getMinecraftServer", "getHandle");

    /** Methods on the server that return its connection listener. */
    static final List<String> CONNECTION_ACCESSORS = List.of(
        "getConnection", "getServerConnection", "getConnectionListener", "getListener");

    /**
     * Fields on the connection listener that have held its bound endpoints.
     * {@code channels} is the live name on Paper 1.20-26.3; the rest are the variations
     * seen across forks and older builds.
     */
    static final List<String> CHANNEL_FIELDS = List.of(
        "channels", "connectionChannels", "listeners", "endpoints", "futures");

    /**
     * Methods on the connection listener that have returned its bound endpoints.
     * {@code getConnections} is deliberately last: on modern Paper it is not the socket
     * list at all, so every other name must be tried before it.
     */
    static final List<String> CHANNEL_ACCESSORS = List.of(
        "getChannels", "getConnectionChannels", "getListeners", "getEndpoints", "getConnections");

    /**
     * Method on a future that yields the channel.
     *
     * <p>Resolved through {@code getMethod} only, never through a field lookup:
     * {@code net.minecraft.network.Connection} has a public {@code channel} field, so
     * accepting fields here would mistake a connected player for a listening socket.
     */
    static final List<String> CHANNEL_YIELDERS = List.of("channel");

    /** What the walk found, plus an ordered account of how it got there. */
    public record Discovery(List<Channel> channels, List<String> trace) {

        public Discovery {
            channels = List.copyOf(channels);
            trace = List.copyOf(trace);
        }

        public boolean isEmpty() {
            return channels.isEmpty();
        }

        public int size() {
            return channels.size();
        }

        /** The trace as one multi-line string, for a startup log. */
        public String describeTrace() {
            return String.join(System.lineSeparator(), trace);
        }
    }

    /**
     * A place a listening channel might be hiding.
     *
     * @param label        how to describe it in the trace, e.g. {@code "field channels"}
     * @param value        what it holds right now
     * @param channelTyped whether its <em>declared</em> element type names a channel.
     *                     This is the only signal that survives an empty holder, which is
     *                     exactly the state before the socket is bound.
     */
    private record Candidate(String label, Object value, boolean channelTyped) {
    }

    private PaperChannelLocator() {
    }

    /**
     * Locates the listening channels of the running server.
     *
     * <p>Resolution order: Bukkit's server object first (always present, cheap), then
     * the NMS static accessor as a fallback. The Bukkit object is preferred because it
     * does not require the engine to name an obfuscation-sensitive class.
     */
    public static Discovery locate() {
        List<String> trace = new ArrayList<>();
        Object root = bukkitServer(trace);
        if (root == null) {
            root = nmsServer(trace);
        }
        if (root == null) {
            trace.add("no server instance was reachable; cannot locate listening channels");
            return new Discovery(List.of(), trace);
        }
        return locateFrom(root, trace);
    }

    /** Testable entry point: runs the walk from an arbitrary root object. */
    public static Discovery locateFrom(Object root) {
        return locateFrom(root, new ArrayList<>());
    }

    static Discovery locateFrom(Object root, List<String> trace) {
        Objects.requireNonNull(root, "root");
        trace.add("walk root: " + describeType(root));

        Object server = resolveServer(root, trace);
        if (server == null) {
            return new Discovery(List.of(), trace);
        }

        Optional<Object> connection = invokeAny(server, CONNECTION_ACCESSORS, trace);
        if (connection.isEmpty()) {
            // No intermediate hop: some shapes expose the channels on the server object
            // itself. Trying it costs one reflection lookup and turns a hard failure into
            // a working install on those builds, which is the whole point of an ordered
            // walk rather than a single hard-coded accessor path.
            trace.add("no connection listener found on " + describeType(server)
                + "; trying the object itself as the listener (tried " + CONNECTION_ACCESSORS + ")");
            List<Channel> direct = collectChannels(server, trace);
            if (direct.isEmpty()) {
                trace.add("no listening channels were reachable from " + describeType(server));
            }
            return new Discovery(direct, trace);
        }
        trace.add("connection listener: " + describeType(connection.get()));

        List<Channel> channels = collectChannels(connection.get(), trace);
        if (channels.isEmpty()) {
            trace.add("the connection listener exposed no bound channels; tried fields "
                + CHANNEL_FIELDS + " and accessors " + CHANNEL_ACCESSORS);
        } else {
            trace.add("located " + channels.size() + " listening channel(s)");
        }
        return new Discovery(channels, trace);
    }

    /** Steps from the root (which may be CraftServer) to the server instance. */
    private static Object resolveServer(Object root, List<String> trace) {
        // Bukkit.getServer() returns CraftServer, not the NMS server. Walk to it if an
        // accessor exists; otherwise treat the root as the server itself.
        Optional<Object> nms = invokeAny(root, SERVER_ACCESSORS, null);
        if (nms.isPresent() && nms.get() != root) {
            trace.add("server instance: " + describeType(nms.get()));
            return nms.get();
        }
        if (nms.isPresent()) {
            // An accessor returned the same object (a self-returning shim); accept it.
            trace.add("root is itself the server instance: " + describeType(root));
            return root;
        }
        trace.add("no server accessor matched on " + describeType(root)
            + "; treating it as the server instance directly");
        return root;
    }

    /** Pulls channels out of whatever shape the listener returned. */
    private static List<Channel> collectChannels(Object listener, List<String> trace) {
        List<Candidate> candidates = candidatesFor(listener, trace);

        for (Candidate candidate : candidates) {
            List<Channel> found = new ArrayList<>();
            appendChannels(candidate.value(), found, trace);
            if (!found.isEmpty()) {
                trace.add("channels via " + candidate.label() + ": "
                    + describeType(candidate.value()));
                return found;
            }
            if (candidate.channelTyped()) {
                // The holder is real and its element type says channels, but it is empty.
                // On a live server that means one thing: the listener has not bound yet,
                // which is a retry, not a broken walk.
                trace.add("channels via " + candidate.label() + ": "
                    + describeType(candidate.value()) + " (channel-typed but empty so far)");
            }
        }
        return List.of();
    }

    /**
     * Every place the listener might keep its bound endpoints, most trustworthy first.
     *
     * <p>Fields come before accessors because the modern shape is field-only. Within each
     * group the declared order is kept, and a stable sort then lifts every candidate whose
     * element type names a channel to the front -- so a well-typed holder wins on shape
     * while a merely well-named one still beats an untyped anonymous collection.
     */
    private static List<Candidate> candidatesFor(Object listener, List<String> trace) {
        List<Candidate> candidates = new ArrayList<>();

        for (Field field : instanceFields(listener.getClass())) {
            boolean named = CHANNEL_FIELDS.contains(field.getName());
            boolean typed = canHoldChannels(field.getGenericType());
            if (!named && !typed) {
                continue;
            }
            readField(listener, field, trace).ifPresent(value ->
                candidates.add(new Candidate("field " + field.getName(), value, typed)));
        }

        for (String accessor : CHANNEL_ACCESSORS) {
            noArgMethod(listener, accessor).ifPresent(method -> {
                invoke(listener, accessor, trace).ifPresent(value -> candidates.add(
                    new Candidate(accessor + "()", value, canHoldChannels(method.getGenericReturnType()))));
            });
        }

        // A holder that is neither named nor typed above, on a build whose names we have
        // simply never seen. Restricted to methods that declare a channel element type, so
        // this cannot pick up an unrelated collection such as the login list.
        for (Method method : listener.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                || !Modifier.isPublic(method.getModifiers())
                || method.getDeclaringClass() == Object.class
                || CHANNEL_ACCESSORS.contains(method.getName())
                || !canHoldChannels(method.getGenericReturnType())) {
                continue;
            }
            invoke(listener, method.getName(), trace).ifPresent(value ->
                candidates.add(new Candidate(method.getName() + "()", value, true)));
        }

        candidates.sort(Comparator.comparing(Candidate::channelTyped).reversed());
        return candidates;
    }

    private static void appendChannels(Object container, List<Channel> sink, List<String> trace) {
        if (container instanceof Channel channel) {
            sink.add(channel);
            return;
        }
        List<Object> elements = elementsOf(container);
        if (elements.isEmpty() && !(container instanceof Iterable<?>) && !container.getClass().isArray()) {
            trace.add("  " + describeType(container) + " is not a collection of channels");
            return;
        }
        for (Object element : elements) {
            Channel channel = channelOf(element, trace);
            if (channel != null) {
                sink.add(channel);
            } else {
                trace.add("  ignored element of type " + describeType(element)
                    + " (not a channel or channel future)");
            }
        }
    }

    /**
     * The channel behind one element of a holder, or {@code null} if it is not one.
     *
     * <p>A {@link ChannelFuture} is unwrapped through the <em>interface</em>, deliberately.
     * Netty 4.2 declares {@code CompleteChannelFuture} and {@code SucceededChannelFuture}
     * package-private, so a public method inherited from them -- {@code channel()} included
     * -- is not invocable by ordinary reflection; the exception reads
     * {@code IllegalAccessException: ... cannot access a member of class
     * io.netty.channel.CompleteChannelFuture with modifiers "public"}. Calling the interface
     * method is both the robust route and the correct one, since the interface is the
     * contract the engine actually depends on.
     *
     * <p>The reflective fallback after it keeps the walk working against fixtures and
     * foreign holders that expose a {@code channel()} method without implementing
     * {@link ChannelFuture}. It stays method-only: see {@link #CHANNEL_YIELDERS}.
     */
    private static Channel channelOf(Object element, List<String> trace) {
        if (element instanceof Channel channel) {
            return channel;
        }
        if (element instanceof ChannelFuture future) {
            return future.channel();
        }
        Optional<Object> unwrapped = invokeAny(element, CHANNEL_YIELDERS, trace);
        return unwrapped.isPresent() && unwrapped.get() instanceof Channel channel ? channel : null;
    }

    /** Flattens an {@link Iterable} or an array into a list; anything else yields empty. */
    private static List<Object> elementsOf(Object container) {
        List<Object> elements = new ArrayList<>();
        if (container instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                elements.add(element);
            }
            return elements;
        }
        if (container.getClass().isArray()) {
            int length = Array.getLength(container);
            for (int i = 0; i < length; i++) {
                elements.add(Array.get(container, i));
            }
        }
        return elements;
    }

    /**
     * True when a declared type could hold channels: an array or collection whose element
     * type names a {@code Channel} or {@code ChannelFuture}.
     *
     * <p>An erased or unparameterised collection returns false rather than true. The point
     * of the check is to distinguish the socket list from the login list, and a bare
     * {@code List} carries no evidence either way.
     */
    private static boolean canHoldChannels(Type type) {
        if (type instanceof GenericArrayType array) {
            return isChannelType(array.getGenericComponentType());
        }
        Class<?> raw = rawClass(type);
        if (raw == null) {
            return false;
        }
        if (raw.isArray()) {
            return isChannelType(raw.getComponentType());
        }
        if (!Iterable.class.isAssignableFrom(raw) || !(type instanceof ParameterizedType parameterized)) {
            return false;
        }
        for (Type argument : parameterized.getActualTypeArguments()) {
            if (isChannelType(argument)) {
                return true;
            }
        }
        return false;
    }

    /** True when a type names a netty channel or a future that resolves to one. */
    private static boolean isChannelType(Type type) {
        Class<?> raw = rawClass(type);
        if (raw == null || raw == Object.class) {
            // An unbounded wildcard erases to Object; it is not evidence of a channel.
            return false;
        }
        return Channel.class.isAssignableFrom(raw) || ChannelFuture.class.isAssignableFrom(raw);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = rawClass(array.getGenericComponentType());
            return component == null ? null : component.arrayType();
        }
        if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length == 1) {
            return rawClass(wildcard.getUpperBounds()[0]);
        }
        return null;
    }

    /** Non-static declared fields of the class and every superclass. */
    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Optional<Object> readField(Object target, Field field, List<String> trace) {
        try {
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            return Optional.ofNullable(field.get(target));
        } catch (RuntimeException | IllegalAccessException failure) {
            if (trace != null) {
                trace.add("  field " + field.getName() + " on " + describeType(target)
                    + " could not be read: " + failure);
            }
            return Optional.empty();
        }
    }

    private static Object bukkitServer(List<String> trace) {
        try {
            return org.bukkit.Bukkit.getServer();
        } catch (RuntimeException | LinkageError unavailable) {
            trace.add("Bukkit.getServer() unavailable: " + unavailable);
            return null;
        }
    }

    private static Object nmsServer(List<String> trace) {
        try {
            Class<?> clazz = Class.forName("net.minecraft.server.MinecraftServer", false,
                PaperChannelLocator.class.getClassLoader());
            for (Method method : clazz.getMethods()) {
                if (method.getParameterCount() == 0
                    && Modifier.isStatic(method.getModifiers())
                    && clazz.isAssignableFrom(method.getReturnType())) {
                    Object instance = method.invoke(null);
                    if (instance != null) {
                        trace.add("server instance via " + clazz.getSimpleName() + "." + method.getName() + "()");
                        return instance;
                    }
                }
            }
            trace.add("net.minecraft.server.MinecraftServer had no static self-accessor");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            trace.add("could not reach the NMS server: " + failure);
        }
        return null;
    }

    /** First matching no-arg accessor, or empty. Records each failed attempt when a trace is given. */
    private static Optional<Object> invokeAny(Object target, List<String> names, List<String> trace) {
        if (target == null) {
            return Optional.empty();
        }
        for (String name : names) {
            Optional<Object> result = invoke(target, name, trace);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> noArgMethod(Object target, String name) {
        if (target == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(target.getClass().getMethod(name));
        } catch (NoSuchMethodException absent) {
            return Optional.empty();
        }
    }

    private static Optional<Object> invoke(Object target, String name, List<String> trace) {
        if (target == null) {
            return Optional.empty();
        }
        Method method;
        try {
            method = target.getClass().getMethod(name);
        } catch (NoSuchMethodException absent) {
            return Optional.empty();
        } catch (RuntimeException failure) {
            if (trace != null) {
                trace.add("  " + name + "() on " + describeType(target) + " could not be resolved: " + failure);
            }
            return Optional.empty();
        }
        try {
            // A public method is not automatically invocable: if the class that *declares*
            // it is package-private, reflection rejects the call. Netty 4.2 ships several
            // such classes, so accessibility has to be forced explicitly rather than
            // inferred from the method's own modifiers.
            if (!method.trySetAccessible()) {
                method.setAccessible(true);
            }
            return Optional.ofNullable(method.invoke(target));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
            if (trace != null) {
                trace.add("  " + name + "() on " + describeType(target) + " failed: " + failure);
            }
            return Optional.empty();
        }
    }

    private static String describeType(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        // Simple names are enough for a diagnostic and avoid the noise of lambdas'
        // generated names, which carry no information an operator can act on.
        return type.isAnonymousClass() || type.isSynthetic() ? "anonymous" : type.getSimpleName();
    }
}
