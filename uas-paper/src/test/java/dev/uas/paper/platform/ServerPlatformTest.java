package dev.uas.paper.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The platform decision drives every other scheduling choice, so it is tested directly. */
class ServerPlatformTest {

    @Test
    void theMarkerClassIsTheFoliaOnlyServerClass() {
        // Not a scheduler interface: paper-api ships those on every platform, so they
        // would detect Folia everywhere. RegionizedServer is a server class.
        assertEquals("io.papermc.paper.threadedregions.RegionizedServer",
            ServerPlatform.FOLIA_MARKER_CLASS);
        assertFalse(ServerPlatform.FOLIA_MARKER_CLASS.contains(".scheduler."),
            "a marker inside the scheduler package would match plain Paper too");
    }

    @Test
    void presenceOfTheMarkerMeansFolia() {
        assertEquals(ServerPlatform.FOLIA,
            ServerPlatform.detectFrom(name -> name.equals(ServerPlatform.FOLIA_MARKER_CLASS)));
    }

    @Test
    void absenceOfTheMarkerMeansPaper() {
        assertEquals(ServerPlatform.PAPER, ServerPlatform.detectFrom(name -> false));
        // The match is exact, not a substring: a similarly named class (a fork's own
        // wrapper, or a shaded copy) must not be mistaken for the real marker.
        assertEquals(ServerPlatform.PAPER,
            ServerPlatform.detectFrom(name -> name.equals(ServerPlatform.FOLIA_MARKER_CLASS + "Impl")));
    }

    @Test
    void anUnrelatedClassBeingPresentDoesNotImplyFolia() {
        assertEquals(ServerPlatform.PAPER,
            ServerPlatform.detectFrom(name -> name.startsWith("io.papermc.paper.threadedregions.scheduler.")));
    }

    @Test
    void aNullLoaderIsTreatedAsPaperRatherThanCrashing() {
        assertEquals(ServerPlatform.PAPER, ServerPlatform.detect(null));
    }

    @Test
    void thisTestJvmIsNotFolia() {
        // Sanity: the engine's own test JVM has paper-api but not the server, which is
        // exactly the shape the detection must classify as Paper.
        assertEquals(ServerPlatform.PAPER, ServerPlatform.detect());
    }

    @Test
    void platformFlagsDescribeTheirThreadingModel() {
        assertTrue(ServerPlatform.FOLIA.isRegionised());
        assertFalse(ServerPlatform.FOLIA.isSingleThreaded());
        assertTrue(ServerPlatform.PAPER.isSingleThreaded());
        assertFalse(ServerPlatform.PAPER.isRegionised());
        assertEquals("Folia", ServerPlatform.FOLIA.displayName());
    }

    @Test
    void theVersionStringIsOnlyASecondarySignal() {
        assertTrue(ServerPlatform.versionStringSaysFolia("git-Folia-1.20.4-abc"));
        assertTrue(ServerPlatform.versionStringSaysFolia("FOLIA"));
        assertFalse(ServerPlatform.versionStringSaysFolia("git-Paper-400"));
        assertFalse(ServerPlatform.versionStringSaysFolia(null));
    }
}
