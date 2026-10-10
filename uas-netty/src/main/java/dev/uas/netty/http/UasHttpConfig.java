package dev.uas.netty.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning for the in-pipeline HTTP multiplexer.
 *
 * <p>Defaults are chosen for a memory-constrained Paper instance: a small
 * aggregation limit (assets are streamed, never buffered) and a modest chunk size
 * so a concurrent download cannot multiply into a large allocation.
 */
public record UasHttpConfig(
    String pathPrefix,
    int maxAggregatedRequestBytes,
    int streamChunkBytes,
    int maxRangeChunkBytes,
    String cacheControl,
    boolean enableRangeRequests,
    boolean enableConditionalRequests,
    boolean enableCompression,
    Duration sniffTimeout) {

    /** Enough for headers plus a long query string; still tiny. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 16 * 1024;

    /** 64 KiB per in-flight chunk: large enough to be efficient, small enough to bound. */
    public static final int DEFAULT_CHUNK_BYTES = 64 * 1024;

    public UasHttpConfig {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        if (!pathPrefix.startsWith("/")) {
            throw new IllegalArgumentException("path prefix must start with '/', got '" + pathPrefix + "'");
        }
        if (!pathPrefix.endsWith("/")) {
            throw new IllegalArgumentException("path prefix must end with '/', got '" + pathPrefix + "'");
        }
        if (maxAggregatedRequestBytes <= 0) {
            throw new IllegalArgumentException("maxAggregatedRequestBytes must be > 0");
        }
        if (streamChunkBytes <= 0) {
            throw new IllegalArgumentException("streamChunkBytes must be > 0");
        }
        if (maxRangeChunkBytes < streamChunkBytes) {
            throw new IllegalArgumentException(
                "maxRangeChunkBytes (" + maxRangeChunkBytes + ") must be >= streamChunkBytes ("
                    + streamChunkBytes + ")");
        }
        Objects.requireNonNull(cacheControl, "cacheControl");
        Objects.requireNonNull(sniffTimeout, "sniffTimeout");
        if (sniffTimeout.isNegative() || sniffTimeout.isZero()) {
            throw new IllegalArgumentException("sniffTimeout must be positive");
        }
    }

    public static UasHttpConfig defaults() {
        return new UasHttpConfig(
            "/uas/pack/",
            DEFAULT_MAX_REQUEST_BYTES,
            DEFAULT_CHUNK_BYTES,
            512 * 1024,
            // Immutable content: the pack id embeds a content hash and a rotated pack
            // gets a new id, so clients may cache it forever.
            "public, max-age=31536000, immutable",
            true,
            true,
            true,
            Duration.ofSeconds(10));
    }

    /** The constrained-RAM variant: smaller chunks, no compression negotiation. */
    public static UasHttpConfig lowRam() {
        return new UasHttpConfig(
            "/uas/pack/",
            8 * 1024,
            16 * 1024,
            128 * 1024,
            "public, max-age=31536000, immutable",
            true,
            true,
            false,
            Duration.ofSeconds(10));
    }

    public Builder toBuilder() {
        return new Builder()
            .pathPrefix(pathPrefix)
            .maxAggregatedRequestBytes(maxAggregatedRequestBytes)
            .streamChunkBytes(streamChunkBytes)
            .maxRangeChunkBytes(maxRangeChunkBytes)
            .cacheControl(cacheControl)
            .enableRangeRequests(enableRangeRequests)
            .enableConditionalRequests(enableConditionalRequests)
            .enableCompression(enableCompression)
            .sniffTimeout(sniffTimeout);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Extracts the pack id from a request path, or empty when it is not a pack request. */
    public java.util.Optional<String> packIdFor(String requestPath) {
        if (requestPath == null || !requestPath.startsWith(pathPrefix)) {
            return java.util.Optional.empty();
        }
        String remainder = requestPath.substring(pathPrefix.length());
        int slash = remainder.indexOf('/');
        if (slash >= 0) {
            remainder = remainder.substring(0, slash);
        }
        // Strip a query string if one was appended.
        int query = remainder.indexOf('?');
        if (query >= 0) {
            remainder = remainder.substring(0, query);
        }
        return remainder.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(remainder);
    }

    public static final class Builder {
        private String pathPrefix = "/uas/pack/";
        private int maxAggregatedRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        private int streamChunkBytes = DEFAULT_CHUNK_BYTES;
        private int maxRangeChunkBytes = 512 * 1024;
        private String cacheControl = "public, max-age=31536000, immutable";
        private boolean enableRangeRequests = true;
        private boolean enableConditionalRequests = true;
        private boolean enableCompression = true;
        private Duration sniffTimeout = Duration.ofSeconds(10);

        public Builder pathPrefix(String value) {
            this.pathPrefix = value;
            return this;
        }

        public Builder maxAggregatedRequestBytes(int value) {
            this.maxAggregatedRequestBytes = value;
            return this;
        }

        public Builder streamChunkBytes(int value) {
            this.streamChunkBytes = value;
            return this;
        }

        public Builder maxRangeChunkBytes(int value) {
            this.maxRangeChunkBytes = value;
            return this;
        }

        public Builder cacheControl(String value) {
            this.cacheControl = value;
            return this;
        }

        public Builder enableRangeRequests(boolean value) {
            this.enableRangeRequests = value;
            return this;
        }

        public Builder enableConditionalRequests(boolean value) {
            this.enableConditionalRequests = value;
            return this;
        }

        public Builder enableCompression(boolean value) {
            this.enableCompression = value;
            return this;
        }

        public Builder sniffTimeout(Duration value) {
            this.sniffTimeout = value;
            return this;
        }

        public UasHttpConfig build() {
            return new UasHttpConfig(
                pathPrefix, maxAggregatedRequestBytes, streamChunkBytes, maxRangeChunkBytes,
                cacheControl, enableRangeRequests, enableConditionalRequests, enableCompression, sniffTimeout);
        }
    }
}
