package dev.uas.netty.http;

import dev.uas.netty.pipeline.PipelineNames;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The first handler on every accepted Minecraft connection. Decides, from the
 * leading bytes, whether the peer is speaking Minecraft or HTTP, then hands the
 * buffered bytes to the right pipeline and deletes itself.
 *
 * <h2>How the decision is made</h2>
 *
 * <p>The engine compares the leading bytes against the complete HTTP method tokens
 * (including the space that terminates them): {@code GET }, {@code POST },
 * {@code HEAD }, {@code PUT }, {@code DELETE }, {@code OPTIONS }, {@code PATCH },
 * {@code CONNECT }, {@code TRACE }.
 *
 * <p>Three properties make this exact rather than heuristic:
 *
 * <ol>
 *   <li><strong>Partial prefixes wait.</strong> If the bytes seen so far match a
 *       <em>prefix</em> of a token, the handler keeps collecting. It decides as soon
 *       as the prefix matches a full token, or as soon as the prefix cannot match any
 *       token -- so a request line split across TCP segments is still classified
 *       correctly, and no fixed byte count is ever demanded.</li>
 *   <li><strong>No method token is a prefix of another</strong>, so a full-token
 *       match is unambiguous.</li>
 *   <li><strong>A vanilla handshake can never be misread as HTTP.</strong> The second
 *       byte of a Minecraft handshake is always {@code 0x00}, the packet id. Every
 *       HTTP method token has {@code 'E'}, {@code 'O'}, {@code 'U'}, {@code 'P'},
 *       {@code 'A'}, {@code 'R'} or {@code 'S'} in that position, so the two sets are
 *       disjoint and a Minecraft connection is classified as Minecraft on its second
 *       byte at the latest, with no heuristic involved.</li>
 * </ol>
 *
 * <p>Legacy server-list pings (a bare {@code 0xFE}, which can never begin an HTTP
 * verb) short-circuit to the Minecraft path immediately.
 *
 * <h2>Why this is not a {@code ByteToMessageDecoder}</h2>
 *
 * <p>An earlier revision extended {@code ByteToMessageDecoder} and relied on its
 * documented re-entrant-removal path (remove during {@code decode}, let the decoder
 * fire the produced list). That works in isolation but interacts badly with the
 * decoder's own cumulation buffer: removing the handler mid-{@code decode} defers
 * {@code handlerRemoved}, and the ownership of the accumulated bytes then depends on
 * internals of the decoder rather than on this class. Measured on real sockets, the
 * classified-as-HTTP bytes never reached the HTTP codec at all.
 *
 * <p>So the accumulation is explicit instead. Leading bytes are gathered into a
 * {@link CompositeByteBuf} of the <em>original</em> buffers -- no copying -- and the
 * handoff is a plain {@code ctx.fireChannelRead} from a live context, before the
 * handler removes itself. Every step is visible in this file, and the ordering
 * constraint that matters (HTTP handlers installed, then bytes fired, then removal)
 * is expressed directly.
 */
public final class HttpProtocolSniffer extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = Logger.getLogger(HttpProtocolSniffer.class.getName());

    /** Every HTTP method token we accept, including its terminating space. */
    private static final byte[][] HTTP_METHOD_TOKENS = {
        "GET ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "POST ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "HEAD ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "PUT ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "DELETE ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "OPTIONS ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "PATCH ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "CONNECT ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "TRACE ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
    };

    /** Length of the longest token above; nothing is decided from fewer bytes than this. */
    static final int MAX_TOKEN_LENGTH = 8;

    /** Legacy server-list ping: one byte, then optionally 0x01. */
    private static final int LEGACY_PING_BYTE = 0xFE;

    /** The protocol the peer turned out to be speaking. */
    public enum Detection {
        UNDECIDED,
        MINECRAFT,
        HTTP
    }

    private final UasHttpConfig config;
    private final PackHttpRouter router;
    private final SniffStats stats;

    /**
     * Bytes seen but not yet dispatched. Owned by this handler until handed off, at
     * which point ownership transfers downstream. All access is on the connection's
     * event loop, so no synchronisation is needed.
     */
    private CompositeByteBuf pending;
    private Detection detection = Detection.UNDECIDED;
    private boolean timeoutScheduled;
    private boolean connectionRecorded;
    private int observedBytes;

    public HttpProtocolSniffer(UasHttpConfig config, PackHttpRouter router, SniffStats stats) {
        this.config = config;
        this.router = router;
        this.stats = stats;
    }

    public Detection detection() {
        return detection;
    }

    public int observedBytes() {
        return observedBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (detection != Detection.UNDECIDED) {
            // Only reachable if removal were vetoed; stay transparent rather than stall.
            ctx.fireChannelRead(msg);
            return;
        }
        if (!(msg instanceof ByteBuf buffer)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (pending == null) {
            pending = ctx.alloc().compositeBuffer();
        }
        int incoming = buffer.readableBytes();
        // Transfers ownership of `buffer` to the composite: no copy, no extra retain.
        pending.addComponent(true, buffer);
        observedBytes += incoming;
        if (!connectionRecorded) {
            connectionRecorded = true;
            stats.recordConnection();
        }
        scheduleSniffTimeout(ctx);

        Detection decision = classifyBuffer(pending);
        switch (decision) {
            case MINECRAFT -> passThroughToVanilla(ctx);
            case HTTP -> switchToHttp(ctx);
            case UNDECIDED -> stats.recordAmbiguousBytes(pending.readableBytes());
        }
    }

    /** Classifies the accumulated prefix without consuming it. */
    private Detection classifyBuffer(ByteBuf buffer) {
        int available = buffer.readableBytes();
        if (available == 0) {
            return Detection.UNDECIDED;
        }
        int base = buffer.readerIndex();
        if ((buffer.getUnsignedByte(base) & 0xFF) == LEGACY_PING_BYTE) {
            return Detection.MINECRAFT;
        }
        int inspect = Math.min(available, MAX_TOKEN_LENGTH);
        boolean prefixOfSomeToken = false;
        for (byte[] token : HTTP_METHOD_TOKENS) {
            int compare = Math.min(inspect, token.length);
            boolean matches = true;
            for (int i = 0; i < compare; i++) {
                if (buffer.getByte(base + i) != token[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            if (inspect >= token.length) {
                return Detection.HTTP;
            }
            prefixOfSomeToken = true;
        }
        return prefixOfSomeToken ? Detection.UNDECIDED : Detection.MINECRAFT;
    }

    /** Detaches and lets the buffered bytes flow into the untouched vanilla pipeline. */
    private void passThroughToVanilla(ChannelHandlerContext ctx) {
        detection = Detection.MINECRAFT;
        stats.recordMinecraft();
        ByteBuf handoff = takePending();
        // Fire while this context is still live, then remove: the downstream walk is
        // guaranteed valid, and later reads bypass this handler entirely.
        if (handoff.isReadable()) {
            ctx.fireChannelRead(handoff);
            ctx.fireChannelReadComplete();
        } else {
            handoff.release();
        }
        ctx.pipeline().remove(this);
        LOGGER.log(Level.FINEST, () -> "UAS classified a connection as Minecraft after "
            + observedBytes + " byte(s); multiplexer detached");
    }

    /** Rebuilds the pipeline for HTTP and replays the buffered bytes into the codec. */
    private void switchToHttp(ChannelHandlerContext ctx) {
        detection = Detection.HTTP;
        stats.recordHttp();

        ChannelPipeline pipeline = ctx.pipeline();
        List<String> detached = detachableHandlerNames(pipeline, this);
        for (String name : detached) {
            pipeline.remove(name);
        }

        // Appended after this handler, so with the vanilla handlers gone they are the
        // first thing the replayed bytes meet.
        pipeline.addLast(PipelineNames.HTTP_CODEC,
            new HttpServerCodec(4096, 8192, config.maxAggregatedRequestBytes()));
        pipeline.addLast(PipelineNames.HTTP_AGGREGATOR,
            new HttpObjectAggregator(config.maxAggregatedRequestBytes()));
        pipeline.addLast(PipelineNames.HTTP_CHUNKED_WRITER, new ChunkedWriteHandler());
        pipeline.addLast(PipelineNames.HTTP_HANDLER, new UasHttpPackHandler(config, router));

        ByteBuf handoff = takePending();
        if (handoff.isReadable()) {
            ctx.fireChannelRead(handoff);
            ctx.fireChannelReadComplete();
        } else {
            handoff.release();
        }
        pipeline.remove(this);

        LOGGER.log(Level.FINEST, () -> "UAS classified a connection as HTTP after " + observedBytes
            + " byte(s); detached " + detached + " and installed the pack streamer");
    }

    /**
     * Names of the handlers that must be detached to make room for the HTTP stack:
     * everything except this sniffer and the pipeline's own head and tail.
     *
     * <p>The head and tail are filtered explicitly rather than by name. Netty's
     * {@code names()} includes the generated tail context, which cannot be removed,
     * and removing it by name is what an earlier revision got wrong -- the failure
     * surfaced as a {@code NoSuchElementException} on the event loop and a silently
     * dropped connection.
     */
    static List<String> detachableHandlerNames(ChannelPipeline pipeline, ChannelHandler self) {
        ChannelHandlerContext head = pipeline.firstContext();
        ChannelHandlerContext tail = pipeline.lastContext();
        List<String> names = new ArrayList<>();
        for (String name : List.copyOf(pipeline.names())) {
            ChannelHandlerContext candidate = pipeline.context(name);
            if (candidate == null || candidate == head || candidate == tail) {
                continue;
            }
            if (candidate.handler() == self) {
                continue;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Relinquishes the accumulated buffer to the caller. */
    private ByteBuf takePending() {
        CompositeByteBuf taken = pending;
        pending = null;
        if (taken == null) {
            return io.netty.buffer.Unpooled.EMPTY_BUFFER;
        }
        return taken;
    }

    private void releasePending() {
        CompositeByteBuf toRelease = pending;
        pending = null;
        if (toRelease != null) {
            toRelease.release();
        }
    }

    /**
     * Closes connections that open and never speak.
     *
     * <p>The sniffer sits ahead of the vanilla read-timeout handler, so it provides
     * its own: without this, a peer that opens a socket and sends nothing would hold
     * a handler and its accumulated buffer indefinitely, because the downstream
     * timeout never sees a read.
     */
    private void scheduleSniffTimeout(ChannelHandlerContext ctx) {
        if (timeoutScheduled) {
            return;
        }
        timeoutScheduled = true;
        ctx.channel().eventLoop().schedule(() -> {
            if (detection == Detection.UNDECIDED && ctx.channel().isActive()) {
                stats.recordTimeoutClose();
                LOGGER.log(Level.FINEST,
                    "UAS closed a connection that sent nothing within the sniff timeout");
                ctx.channel().close();
            }
        }, config.sniffTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releasePending();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releasePending();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        releasePending();
        LOGGER.log(Level.FINE, "UAS sniffer closing a connection after an exception", cause);
        ctx.close();
    }

    /** True while bytes are held awaiting classification. */
    public boolean isHoldingBytes() {
        return pending != null && pending.readableBytes() > 0;
    }

    /** Exposed so tests can assert the classification rule without a socket. */
    public static Detection classify(byte[] leadingBytes) {
        if (leadingBytes == null || leadingBytes.length == 0) {
            return Detection.UNDECIDED;
        }
        if ((leadingBytes[0] & 0xFF) == LEGACY_PING_BYTE) {
            return Detection.MINECRAFT;
        }
        int inspect = Math.min(leadingBytes.length, MAX_TOKEN_LENGTH);
        boolean prefixOfSomeToken = false;
        for (byte[] token : HTTP_METHOD_TOKENS) {
            int compare = Math.min(inspect, token.length);
            boolean matches = true;
            for (int i = 0; i < compare; i++) {
                if (leadingBytes[i] != token[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            if (inspect >= token.length) {
                return Detection.HTTP;
            }
            prefixOfSomeToken = true;
        }
        return prefixOfSomeToken ? Detection.UNDECIDED : Detection.MINECRAFT;
    }

    /** The HTTP method tokens this build accepts, for diagnostics and tests. */
    public static List<String> supportedHttpMethods() {
        List<String> methods = new ArrayList<>(HTTP_METHOD_TOKENS.length);
        for (byte[] token : HTTP_METHOD_TOKENS) {
            methods.add(new String(token, java.nio.charset.StandardCharsets.US_ASCII).trim());
        }
        return List.copyOf(methods);
    }
}
