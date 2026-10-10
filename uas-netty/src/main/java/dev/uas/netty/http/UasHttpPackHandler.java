package dev.uas.netty.http;

import dev.uas.api.pack.PackSource;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves compiled packs over the vanilla Minecraft port.
 *
 * <p>Body bytes never touch the heap in aggregate: the response is written as a
 * {@link ChunkedFile} pulled through {@code ChunkedWriteHandler}, which reads from
 * the file channel into one reusable chunk buffer at a time. A 400 MB pack and a
 * 4 KB pack cost the same heap.
 *
 * <p>The handler owns exactly one open file handle at a time and is responsible for
 * closing it on every exit path -- completion, failure, and channel teardown.
 */
public final class UasHttpPackHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOGGER = Logger.getLogger(UasHttpPackHandler.class.getName());
    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(java.time.ZoneOffset.UTC);

    private final UasHttpConfig config;
    private final PackHttpRouter router;

    /** The single in-flight stream for this connection; closed on every exit path. */
    private ChunkedInput<?> activeStream;

    public UasHttpPackHandler(UasHttpConfig config, PackHttpRouter router) {
        this.config = config;
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            sendStatus(ctx, request, HttpResponseStatus.BAD_REQUEST, "malformed request");
            return;
        }
        HttpMethod method = request.method();
        boolean head = HttpMethod.HEAD.equals(method);
        if (!head && !HttpMethod.GET.equals(method)) {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
            response.headers().set(HttpHeaderNames.ALLOW, "GET, HEAD");
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            HttpUtil.setContentLength(response, 0);
            finish(ctx, request, response, false);
            return;
        }

        if (HttpUtil.is100ContinueExpected(request)) {
            HttpResponse cont = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
            HttpUtil.setContentLength(cont, 0);
            ctx.write(cont);
        }

        String path = stripQuery(request.uri());
        if ("/uas/ping".equals(path)) {
            sendText(ctx, request, HttpResponseStatus.OK, "uas/1\n", head);
            return;
        }
        if ("/uas/packs".equals(path)) {
            sendText(ctx, request, HttpResponseStatus.OK, describePacks(), head);
            return;
        }

        Optional<String> packId = config.packIdFor(path);
        if (packId.isEmpty()) {
            sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND, "unknown path\n");
            return;
        }
        Optional<PackSource> resolved = router.resolve(packId.get());
        if (resolved.isEmpty()) {
            sendStatus(ctx, request, HttpResponseStatus.NOT_FOUND, "no pack with id '" + packId.get() + "'\n");
            return;
        }
        serve(ctx, request, resolved.get(), head);
    }

    private void serve(ChannelHandlerContext ctx, FullHttpRequest request, PackSource pack, boolean head) {
        long totalLength = pack.size();
        String etag = pack.etag();

        if (config.enableConditionalRequests()
            && EtagMatcher.ifNoneMatch(request.headers().get(HttpHeaderNames.IF_NONE_MATCH), etag)) {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
            applyEntityHeaders(response, pack);
            finish(ctx, request, response, true);
            return;
        }

        HttpRange.Request rangeRequest = config.enableRangeRequests()
            ? HttpRange.parse(request.headers().get(HttpHeaderNames.RANGE), totalLength)
            : HttpRange.Request.absent(null);

        if (rangeRequest.kind() == HttpRange.Kind.UNSATISFIABLE) {
            HttpResponse response =
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, rangeRequest.unsatisfiedContentRange(totalLength));
            applyEntityHeaders(response, pack);
            HttpUtil.setContentLength(response, 0);
            finish(ctx, request, response, false);
            return;
        }

        boolean partial = rangeRequest.isSatisfied();
        long offset = partial ? rangeRequest.range().start() : 0L;
        long bodyLength = partial ? rangeRequest.range().length() : totalLength;
        HttpResponseStatus status = partial ? HttpResponseStatus.PARTIAL_CONTENT : HttpResponseStatus.OK;

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/zip");
        applyEntityHeaders(response, pack);
        if (partial) {
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, rangeRequest.range().contentRange(totalLength));
        }
        HttpUtil.setContentLength(response, bodyLength);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);

        if (head || bodyLength == 0) {
            finish(ctx, request, response, keepAlive);
            return;
        }

        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(pack.path().toFile(), "r");
            HttpChunkedInput stream = new HttpChunkedInput(
                new ChunkedFile(file, offset, bodyLength, config.streamChunkBytes()));
            activeStream = stream;
            ctx.write(response);
            ChannelFuture written = ctx.writeAndFlush(stream);
            written.addListener((ChannelFuture future) -> {
                releaseStream();
                if (!future.isSuccess()) {
                    LOGGER.log(Level.FINE, "UAS pack stream for '" + pack.id() + "' failed", future.cause());
                    future.channel().close();
                } else if (!keepAlive) {
                    future.channel().close();
                }
            });
        } catch (IOException e) {
            releaseStream();
            closeQuietly(file);
            LOGGER.log(Level.WARNING, "UAS could not open pack '" + pack.id() + "' for streaming", e);
            if (ctx.channel().isActive()) {
                sendStatus(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "pack unavailable\n");
            }
        }
    }

    private void applyEntityHeaders(HttpResponse response, PackSource pack) {
        response.headers().set(HttpHeaderNames.ETAG, pack.etag());
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, config.cacheControl());
        response.headers().set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);
        response.headers().set(HttpHeaderNames.DATE, HTTP_DATE.format(ZonedDateTime.now(java.time.ZoneOffset.UTC)));
        long modified = pack.lastModifiedMillis();
        if (modified > 0) {
            response.headers().set(
                HttpHeaderNames.LAST_MODIFIED,
                HTTP_DATE.format(java.time.Instant.ofEpochMilli(modified)));
        }
        if (config.enableCompression()) {
            // Never actually compressed: a pack is already a deflate archive, and
            // re-compressing it would cost CPU for nothing. Advertising identity
            // explicitly stops an intermediary from trying.
            response.headers().set(HttpHeaderNames.VARY, "accept-encoding");
        }
    }

    private void sendStatus(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
        String message) {
        boolean head = HttpMethod.HEAD.equals(request.method());
        sendText(ctx, request, status, message, head);
    }

    private void sendText(
        ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String body, boolean head) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(response, bytes.length);
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);
        if (head) {
            finish(ctx, request, response, keepAlive);
            return;
        }
        ctx.write(response);
        ChannelFuture written = ctx.writeAndFlush(new DefaultLastHttpContent(Unpooled.wrappedBuffer(bytes)));
        if (!keepAlive) {
            written.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void finish(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponse response, boolean keepAlive) {
        ctx.write(response);
        ChannelFuture written = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (!keepAlive) {
            written.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String describePacks() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : new java.util.TreeSet<>(router.knownPackIds())) {
            Optional<PackSource> source = router.resolve(id);
            if (source.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            PackSource pack = source.get();
            sb.append("{\"id\":\"").append(escape(id))
                .append("\",\"sha1\":\"").append(escape(pack.sha1()))
                .append("\",\"size\":").append(pack.size())
                .append(",\"name\":\"").append(escape(pack.displayName()))
                .append("\"}");
        }
        return sb.append("]\n").toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String stripQuery(String uri) {
        if (uri == null) {
            return "/";
        }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        int fragment = path.indexOf('#');
        return fragment < 0 ? path : path.substring(0, fragment);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseStream();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releaseStream();
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        releaseStream();
        LOGGER.log(Level.FINE, "UAS HTTP handler closing connection after an exception", cause);
        ctx.close();
    }

    /** Closes the in-flight stream exactly once, whichever path got here first. */
    private void releaseStream() {
        ChunkedInput<?> stream = activeStream;
        activeStream = null;
        if (stream != null) {
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(ChunkedInput<?> stream) {
        try {
            stream.close();
        } catch (Exception e) {
            LOGGER.log(Level.FINEST, "ignoring error while closing a pack stream", e);
        }
    }

    private static void closeQuietly(RandomAccessFile file) {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINEST, "ignoring error while closing a pack file", e);
        }
    }

    /** Exposed for diagnostics and tests. */
    public boolean hasActiveStream() {
        return activeStream != null;
    }

    @Override
    public String toString() {
        return "UasHttpPackHandler[prefix=" + config.pathPrefix() + ", chunk=" + config.streamChunkBytes() + "]";
    }
}
