package dev.uas.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The advertised pack URL is the one setting whose mistake produces no error anywhere: the
 * pack compiles, registers and serves, and every client is simply handed a URL that nothing
 * answers. These tests pin the check that turns that silence into a log line.
 */
class UasPaperConfigTest {

    private static UasPaperConfig withBaseUrl(String url) {
        return new UasPaperConfig(
            false, url, "prompt", false, false, 4096, 10, 30, 200, true, true);
    }

    @Test
    void theDefaultBaseUrlUsesThePortItWasGiven() {
        // The shipped config.yml must not override this, otherwise a server on any port other
        // than the one the file happens to name advertises a dead URL out of the box.
        assertEquals("http://127.0.0.1:25566/uas/pack/", UasPaperConfig.defaults(25566).publicBaseUrl());
        assertEquals(25566, UasPaperConfig.defaults(25566).advertisedPort());
    }

    @Test
    void aMatchingPortIsSilent() {
        assertTrue(withBaseUrl("http://127.0.0.1:25566/uas/pack/").portMismatchWarning(25566).isEmpty());
    }

    @Test
    void aDifferentPortIsReportedWithBothNumbers() {
        Optional<String> warning =
            withBaseUrl("http://127.0.0.1:25565/uas/pack/").portMismatchWarning(25566);

        assertTrue(warning.isPresent(), "a port mismatch must be reported");
        String text = warning.get();
        assertTrue(text.contains("25565") && text.contains("25566"),
            "the warning must name both the advertised and the actual port: " + text);
        assertTrue(text.contains("public-base-url"), "the warning must name the key to fix: " + text);
    }

    @Test
    void aUrlWithoutAnExplicitPortIsNotAWarning() {
        // https URLs commonly omit the port, and a URL with no port is no evidence of a
        // mismatch. Warning here would train operators to ignore the message.
        assertEquals(-1, withBaseUrl("https://cdn.example.com/uas/pack/").advertisedPort());
        assertTrue(withBaseUrl("https://cdn.example.com/uas/pack/").portMismatchWarning(25566).isEmpty());
    }

    @Test
    void aMalformedUrlIsNotAWarning() {
        // Garbage in the config is not proof that the port is wrong, and the compile step
        // reports it in its own terms.
        assertEquals(-1, withBaseUrl("not a url at all").advertisedPort());
        assertTrue(withBaseUrl("not a url at all").portMismatchWarning(25566).isEmpty());
    }

    @Test
    void aProxyForwardingAPublicPortIsLeftAloneWhenItMatches() {
        // The realistic proxy case: the server binds 25577 locally but is reached on 25565.
        // The operator sets the public port, the server reports the local one, and the check
        // must say so in terms an operator can act on rather than silently passing.
        Optional<String> warning =
            withBaseUrl("http://play.example.com:25566/uas/pack/").portMismatchWarning(25577);

        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("proxy"),
            "the message should acknowledge the proxy case: " + warning.get());
    }

    @Test
    void everyOtherSettingKeepsItsDocumentedDefault() {
        UasPaperConfig config = UasPaperConfig.defaults(25565);
        assertFalse(config.lowMemoryMode());
        assertFalse(config.packRequired(), "a required pack would lock players out on a failed download");
        assertFalse(config.verifyPacksOnRegister());
        assertEquals(4096, config.maxTrackedPlayers());
        assertEquals(10, config.hookRetryIntervalTicks());
        assertEquals(30, config.maxHookAttempts());
        assertEquals(200, config.packSweepIntervalTicks());
        assertTrue(config.ownershipGuard());
        assertTrue(config.logStartupTrace());
    }
}
