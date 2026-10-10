package dev.uas.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for the handler set Paper installs on a client channel, and records
 * exactly what reaches it.
 *
 * <p>Three things are recorded, and each one verifies a separate claim about the
 * multiplexer:
 *
 * <ul>
 *   <li><strong>The bytes themselves</strong>, so a Minecraft handshake can be
 *       asserted byte-for-byte identical after passing the sniffer.</li>
 *   <li><strong>The pipeline snapshot at receive time</strong>, which proves the
 *       sniffer really detached itself and left no per-packet cost behind.</li>
 *   <li><strong>The reference count of each buffer on arrival</strong>, which is
 *       where a Netty leak or a double-free would show up.</li>
 * </ul>
 */
public final class VanillaPipelineRecorder {

    /** Handler names mirroring the vanilla/Paper client pipeline. */
    public static final String TIMEOUT = "timeout";
    public static final String SPLITTER = "splitter";
    public static final String DECODER = "decoder";
    public static final String PREPENDER = "prepender";
    public static final String ENCODER = "encoder";

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<byte[]> chunks = new CopyOnWriteArrayList<>();
    private final List<List<String>> pipelineSnapshots = new CopyOnWriteArrayList<>();
    private final List<Integer> arrivalRefCounts = new CopyOnWriteArrayList<>();
    private final List<ByteBuf> arrivedBuffers = new CopyOnWriteArrayList<>();
    private final AtomicInteger receivedBytes = new AtomicInteger();

    /** Installs the vanilla-like handler set. Called by the server's child initializer. */
    public void install(ChannelPipeline pipeline) {
        pipeline.addLast(TIMEOUT, new ReadTimeoutHandler(30, TimeUnit.SECONDS));
        pipeline.addLast(SPLITTER, new PassThroughInbound());
        pipeline.addLast(DECODER, new Recorder());
        pipeline.addLast(PREPENDER, new PassThroughOutbound());
        pipeline.addLast(ENCODER, new PassThroughOutbound());
    }

    /** Every byte that reached the vanilla pipeline, concatenated. */
    public byte[] capturedBytes() {
        synchronized (captured) {
            return captured.toByteArray();
        }
    }

    public int receivedBytes() {
        return receivedBytes.get();
    }

    public List<byte[]> chunks() {
        return List.copyOf(chunks);
    }

    /** Pipeline contents observed by the terminal handler for each read. */
    public List<List<String>> pipelineSnapshots() {
        return List.copyOf(pipelineSnapshots);
    }

    /** Every handler name ever observed by the terminal handler. */
    public List<String> observedHandlerNames() {
        return pipelineSnapshots.stream().flatMap(List::stream).distinct().toList();
    }

    /** The pipeline as seen on the most recent delivery. */
    public List<String> lastPipelineSnapshot() {
        List<List<String>> snapshots = pipelineSnapshots();
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("the vanilla pipeline has received nothing yet");
        }
        return snapshots.get(snapshots.size() - 1);
    }

    /** How many deliveries saw a handler with this name installed. */
    public long deliveriesSeeing(String handlerName) {
        return pipelineSnapshots.stream().filter(snapshot -> snapshot.contains(handlerName)).count();
    }

    public List<Integer> arrivalRefCounts() {
        return List.copyOf(arrivalRefCounts);
    }

    /** Buffers as they arrived, for post-hoc reference-count inspection. */
    public List<ByteBuf> arrivedBuffers() {
        return List.copyOf(arrivedBuffers);
    }

    public boolean awaitBytes(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (receivedBytes.get() >= expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return receivedBytes.get() >= expected;
    }

    /** Forwards a message unchanged, emulating a vanilla handler that only inspects it. */
    private static final class PassThroughInbound extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }
    }

    /** Emulates the outbound vanilla handlers; nothing in these tests writes. */
    private static final class PassThroughOutbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.write(msg, promise);
        }
    }

    /**
     * The terminal vanilla handler. Records, releases, and verifies in one place so
     * the reference-count contract is asserted rather than assumed.
     */
    private final class Recorder extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buffer)) {
                ctx.fireChannelRead(msg);
                return;
            }
            pipelineSnapshots.add(List.copyOf(ctx.pipeline().names()));
            int refCount = buffer.refCnt();
            arrivalRefCounts.add(refCount);

            int readable = buffer.readableBytes();
            byte[] copy = new byte[readable];
            buffer.readBytes(copy);
            chunks.add(copy);
            synchronized (captured) {
                captured.write(copy, 0, copy.length);
            }
            receivedBytes.addAndGet(readable);

            // Exactly one reference is expected here: the relaying handler owns the
            // buffer and hands ownership on. Zero would mean a double free upstream,
            // more than one would mean the sniffer over-retained.
            buffer.release();
            arrivedBuffers.add(buffer);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
