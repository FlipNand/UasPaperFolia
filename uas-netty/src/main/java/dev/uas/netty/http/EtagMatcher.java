package dev.uas.netty.http;

import java.util.Locale;

/**
 * {@code If-None-Match} evaluation for conditional GETs.
 *
 * <p>Resource packs are re-requested every time a player joins, so this is the
 * hottest path in the engine: a correct 304 turns a multi-megabyte download into a
 * few dozen bytes. Matching therefore follows RFC 9110 exactly, including weak
 * comparison -- our tags are content hashes, so weak and strong comparison agree,
 * but a proxy may have downgraded the tag on the way and we must still hit.
 */
public final class EtagMatcher {

    private EtagMatcher() {
    }

    /**
     * @param headerValue the raw {@code If-None-Match} value, may be {@code null}
     * @param currentEtag the entity tag we would send today
     * @return true when the client's cached copy is still current
     */
    public static boolean ifNoneMatch(String headerValue, String currentEtag) {
        if (headerValue == null || headerValue.isBlank()) {
            return false;
        }
        String normalizedTarget = normalize(currentEtag);
        for (String candidate : headerValue.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("*".equals(trimmed)) {
                return true;
            }
            if (normalize(trimmed).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strong comparison for {@code If-Match}: weak tags never match, which is what
     * makes a range-append safe against a pack that changed mid-download.
     */
    public static boolean ifMatch(String headerValue, String currentEtag) {
        if (headerValue == null || headerValue.isBlank()) {
            return true;
        }
        String normalizedTarget = normalize(currentEtag);
        for (String candidate : headerValue.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (isWeak(trimmed)) {
                continue;
            }
            if (normalize(trimmed).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeak(String tag) {
        return tag.regionMatches(true, 0, "W/", 0, 2);
    }

    private static String normalize(String etag) {
        String value = etag == null ? "" : etag.trim();
        if (isWeak(value)) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
