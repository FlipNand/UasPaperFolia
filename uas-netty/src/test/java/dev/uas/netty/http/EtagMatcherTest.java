package dev.uas.netty.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EtagMatcherTest {

    private static final String TAG = "\"abc123\"";

    @Test
    @DisplayName("an absent or blank header never matches")
    void absentHeader() {
        assertFalse(EtagMatcher.ifNoneMatch(null, TAG));
        assertFalse(EtagMatcher.ifNoneMatch("", TAG));
        assertFalse(EtagMatcher.ifNoneMatch("   ", TAG));
    }

    @Test
    @DisplayName("an exact tag matches, and a wildcard matches anything")
    void exactAndWildcard() {
        assertTrue(EtagMatcher.ifNoneMatch("\"abc123\"", TAG));
        assertTrue(EtagMatcher.ifNoneMatch("*", TAG));
    }

    @Test
    @DisplayName("a weak tag still matches on If-None-Match")
    void weakTags() {
        assertTrue(EtagMatcher.ifNoneMatch("W/\"abc123\"", TAG));
        assertTrue(EtagMatcher.ifNoneMatch("W/\"abc123\"", "W/\"abc123\""));
    }

    @Test
    @DisplayName("a list is searched, and quoting is normalised")
    void lists() {
        assertTrue(EtagMatcher.ifNoneMatch("\"other\", \"abc123\", \"third\"", TAG));
        assertTrue(EtagMatcher.ifNoneMatch("abc123", TAG));
        assertTrue(EtagMatcher.ifNoneMatch("\"abc123\"", "abc123"));
        assertFalse(EtagMatcher.ifNoneMatch("\"other\", \"third\"", TAG));
    }

    @Test
    @DisplayName("a stale tag does not match")
    void stale() {
        assertFalse(EtagMatcher.ifNoneMatch("\"000000\"", TAG));
    }

    @Test
    @DisplayName("If-Match uses strong comparison, so a weak tag never matches")
    void strongComparisonForIfMatch() {
        assertTrue(EtagMatcher.ifMatch(null, TAG), "a missing If-Match is not a precondition failure");
        assertTrue(EtagMatcher.ifMatch("\"abc123\"", TAG));
        assertTrue(EtagMatcher.ifMatch("*", TAG));
        assertFalse(EtagMatcher.ifMatch("W/\"abc123\"", TAG));
        assertFalse(EtagMatcher.ifMatch("\"stale\"", TAG));
    }
}
