package dev.uas.netty.pipeline;

import java.util.Set;

/** Handler names used by the engine, and the vanilla names it has to recognise. */
public final class PipelineNames {

    /** The sniffer, always inserted as the very first handler of a client channel. */
    public static final String MULTIPLEXER = "uas_multiplexer";

    /** Interceptor inserted before Netty's {@code ServerBootstrapAcceptor} on the server channel. */
    public static final String CHILD_HOOK = "uas_child_hook";

    public static final String HTTP_CODEC = "uas_http_codec";
    public static final String HTTP_AGGREGATOR = "uas_http_aggregator";
    public static final String HTTP_CHUNKED_WRITER = "uas_http_chunked";
    public static final String HTTP_HANDLER = "uas_http_handler";

    /**
     * Paper / vanilla handler names on the client channel.
     *
     * <p>Only used for diagnostics: the HTTP switch removes every handler except
     * the sniffer, so a renamed handler in a future server build cannot leave a
     * Minecraft framer in the HTTP path. The names are kept so the engine can log
     * exactly what it detached, which is the first thing you want to know when a
     * build stops working.
     */
    public static final Set<String> VANILLA_CLIENT_HANDLERS = Set.of(
        "timeout",
        "legacy_query",
        "legacy_ping",
        "legacy_splitter",
        "splitter",
        "decoder",
        "prepender",
        "encoder",
        "packet_handler",
        "handler",
        "handler_query",
        "read-timeout",
        "idle-timeout");

    /** Paper's legacy-query handler lives on the server channel, not the client one. */
    public static final String LEGACY_QUERY = "legacy_query";

    private PipelineNames() {
    }
}
