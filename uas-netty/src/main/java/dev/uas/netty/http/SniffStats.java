package dev.uas.netty.http;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters describing what the multiplexer has seen.
 *
 * <p>Deliberately cheap (plain atomics, no maps) because they are incremented on
 * the accept path for every single connection. A non-zero
 * {@link #timeoutClosed()} means clients are opening connections and never
 * speaking, which is the signal that the sniff timeout is doing its job.
 */
public final class SniffStats {

    private final AtomicLong connections = new AtomicLong();
    private final AtomicLong minecraftConnections = new AtomicLong();
    private final AtomicLong httpConnections = new AtomicLong();
    private final AtomicLong ambiguousBytes = new AtomicLong();
    private final AtomicLong timeoutClosed = new AtomicLong();

    void recordConnection() {
        connections.incrementAndGet();
    }

    void recordMinecraft() {
        minecraftConnections.incrementAndGet();
    }

    void recordHttp() {
        httpConnections.incrementAndGet();
    }

    void recordAmbiguousBytes(int bytes) {
        ambiguousBytes.addAndGet(bytes);
    }

    void recordTimeoutClose() {
        timeoutClosed.incrementAndGet();
    }

    public long connections() {
        return connections.get();
    }

    public long minecraftConnections() {
        return minecraftConnections.get();
    }

    public long httpConnections() {
        return httpConnections.get();
    }

    public long ambiguousBytes() {
        return ambiguousBytes.get();
    }

    public long timeoutClosed() {
        return timeoutClosed.get();
    }

    @Override
    public String toString() {
        return "SniffStats[connections=" + connections()
            + ", minecraft=" + minecraftConnections()
            + ", http=" + httpConnections()
            + ", ambiguousBytes=" + ambiguousBytes()
            + ", timeoutClosed=" + timeoutClosed() + "]";
    }
}
