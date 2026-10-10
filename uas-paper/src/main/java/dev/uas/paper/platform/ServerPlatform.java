package dev.uas.paper.platform;

import java.util.Locale;

/**
 * Which server implementation we are running inside.
 *
 * <p>This matters far more than a version string. Paper runs one main thread that
 * owns every world, every entity and every block; the per-player work the engine does
 * (pushing a resource pack, syncing a HUD value, moving a display entity) all queues
 * behind everything else on that single thread. Folia replaces the main thread with a
 * set of <em>region</em> threads, each owning a slice of the world, so the same work
 * is distributed across however many regions the server keeps busy.
 *
 * <p>The engine therefore cannot just call {@code Bukkit.getScheduler()}: on Folia that
 * throws, and even where it did not, posting per-player work to a global thread would
 * throw away the entire point of running Folia. The platform is detected once, at
 * startup, and the scheduler it selects is what every other component goes through.
 *
 * <h2>How detection works</h2>
 *
 * <p>By class presence, not by parsing a version banner. {@code RegionizedServer} is a
 * <em>server</em> class that exists only on Folia; the API classes that merely describe
 * the regionized scheduler (in {@code io.papermc.paper.threadedregions.scheduler}) are
 * shipped by paper-api too and so are useless as a marker. A version string is a
 * worse signal still: it is cosmetic, it can be faked by forks, and a mis-detection
 * here means either a {@code NoSuchMethodError} on the first pack push or, worse, a
 * silent fall back to a single thread on a server that has hundreds.
 */
public enum ServerPlatform {

    /** Vanilla-shaped Paper: one main thread owns all world state. */
    PAPER("Paper", false),

    /** Folia: regionised threading, where each tick thread owns a slice of the world. */
    FOLIA("Folia", true);

    /**
     * Present only on Folia. Referenced by name so the engine never links against it.
     */
    public static final String FOLIA_MARKER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";

    private final String displayName;
    private final boolean regionised;

    ServerPlatform(String displayName, boolean regionised) {
        this.displayName = displayName;
        this.regionised = regionised;
    }

    public String displayName() {
        return displayName;
    }

    /** True when world state is split across multiple owning threads. */
    public boolean isRegionised() {
        return regionised;
    }

    /** True when there is a single main thread that owns all world state. */
    public boolean isSingleThreaded() {
        return !regionised;
    }

    /** Detects the platform using the engine's own class loader. */
    public static ServerPlatform detect() {
        return detect(ServerPlatform.class.getClassLoader());
    }

    /**
     * Detects the platform using an explicit loader.
     *
     * <p>Resolution is deliberately non-initialising ({@code initialize = false}): the
     * check must not run a static initialiser, because on Folia that class wires up the
     * regioniser and loading it early, from a plugin constructor, is exactly the kind
     * of surprise this detection is meant to avoid.
     */
    public static ServerPlatform detect(ClassLoader loader) {
        if (loader == null) {
            return PAPER;
        }
        return detectFrom(name -> {
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                return false;
            }
        });
    }

    /**
     * The decision, split from the class-loading mechanism so it can be tested.
     *
     * <p>Without this seam the only way to test the Folia branch is to actually have a
     * Folia server on the classpath, which means the branch that decides whether every
     * other component takes the regionised path would be the one piece of the platform
     * layer with no coverage at all.
     */
    public static ServerPlatform detectFrom(java.util.function.Predicate<String> classPresent) {
        return classPresent.test(FOLIA_MARKER_CLASS) ? FOLIA : PAPER;
    }

    /**
     * A secondary signal for diagnostics: Folia's {@code Bukkit.getVersion()} contains
     * "Folia". Never used to decide behaviour -- only to report a disagreement between
     * the class check and what the server calls itself.
     */
    public static boolean versionStringSaysFolia(String bukkitVersion) {
        return bukkitVersion != null
            && bukkitVersion.toLowerCase(Locale.ROOT).contains("folia");
    }
}
