package dev.uas.netty.http;

import java.util.Locale;

/**
 * A single HTTP byte range.
 *
 * <p>Only the first range of a multi-range request is honoured. Serving multipart
 * byte ranges would mean interleaving several files into one response body, and a
 * resource pack download is a single linear stream -- clients that ask for
 * multiple ranges are probing, not downloading.
 *
 * <p>Malformed ranges are treated as "no range" (HTTP 200 with the full entity),
 * which is what RFC 9110 requires; only a syntactically valid but unsatisfiable
 * range produces 206's counterpart, 416.
 */
public record HttpRange(long start, long endInclusive) {

    public HttpRange {
        if (start < 0) {
            throw new IllegalArgumentException("range start must be >= 0, got " + start);
        }
        if (endInclusive < start) {
            throw new IllegalArgumentException(
                "range end (" + endInclusive + ") must be >= start (" + start + ")");
        }
    }

    public enum Kind {
        /** No range requested; serve the whole entity. */
        ABSENT,
        /** A satisfiable range; serve 206. */
        SATISFIED,
        /** A valid range that lies outside the entity; serve 416. */
        UNSATISFIABLE
    }

    /** The outcome of parsing a {@code Range} header. */
    public record Request(Kind kind, HttpRange range, String rawHeader) {

        static Request absent(String rawHeader) {
            return new Request(Kind.ABSENT, null, rawHeader);
        }

        static Request satisfied(HttpRange range, String rawHeader) {
            return new Request(Kind.SATISFIED, range, rawHeader);
        }

        static Request unsatisfiable(String rawHeader) {
            return new Request(Kind.UNSATISFIABLE, null, rawHeader);
        }

        public boolean isSatisfied() {
            return kind == Kind.SATISFIED;
        }

        /** The {@code Content-Range} value to send with a 416. */
        public String unsatisfiedContentRange(long contentLength) {
            return "bytes */" + contentLength;
        }
    }

    public long length() {
        return endInclusive - start + 1;
    }

    /** The {@code Content-Range} header value for a 206 response. */
    public String contentRange(long totalLength) {
        return "bytes " + start + "-" + endInclusive + "/" + totalLength;
    }

    public boolean coversWholeEntity(long totalLength) {
        return start == 0 && endInclusive == totalLength - 1;
    }

    /**
     * Parses a {@code Range} header.
     *
     * @param headerValue the raw header value, may be {@code null}
     * @param contentLength the total length of the entity being served
     */
    public static Request parse(String headerValue, long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("content length must be >= 0, got " + contentLength);
        }
        if (headerValue == null || headerValue.isBlank()) {
            return Request.absent(headerValue);
        }
        String value = headerValue.trim();
        int equals = value.indexOf('=');
        if (equals < 0) {
            return Request.absent(headerValue);
        }
        if (!"bytes".equalsIgnoreCase(value.substring(0, equals).trim())) {
            // Other range units are not defined for this resource.
            return Request.absent(headerValue);
        }
        String specification = value.substring(equals + 1).trim();
        int comma = specification.indexOf(',');
        if (comma >= 0) {
            specification = specification.substring(0, comma).trim();
        }
        int dash = specification.indexOf('-');
        if (dash < 0) {
            return Request.absent(headerValue);
        }

        String startText = specification.substring(0, dash).trim();
        String endText = specification.substring(dash + 1).trim();

        try {
            if (startText.isEmpty()) {
                // Suffix form: the last N bytes.
                if (endText.isEmpty()) {
                    return Request.absent(headerValue);
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return Request.unsatisfiable(headerValue);
                }
                long start = Math.max(0, contentLength - suffix);
                if (contentLength == 0) {
                    return Request.unsatisfiable(headerValue);
                }
                return Request.satisfied(new HttpRange(start, contentLength - 1), headerValue);
            }

            long start = Long.parseLong(startText);
            if (start < 0) {
                return Request.absent(headerValue);
            }
            if (start >= contentLength) {
                return Request.unsatisfiable(headerValue);
            }
            long end = endText.isEmpty() ? contentLength - 1 : Long.parseLong(endText);
            if (end < start) {
                return Request.unsatisfiable(headerValue);
            }
            return Request.satisfied(new HttpRange(start, Math.min(end, contentLength - 1)), headerValue);
        } catch (NumberFormatException e) {
            // Digits that overflow a long, or garbage: not a range we understand.
            return Request.absent(headerValue);
        }
    }

    @Override
    public String toString() {
        return "bytes=" + start + "-" + endInclusive;
    }

    static String normalizeEtag(String etag) {
        return etag.toLowerCase(Locale.ROOT).trim();
    }
}
