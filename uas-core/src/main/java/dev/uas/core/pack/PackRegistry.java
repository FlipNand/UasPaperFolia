package dev.uas.core.pack;

import dev.uas.api.pack.PackSource;
import dev.uas.netty.http.PackHttpRouter;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The set of packs the HTTP layer can serve.
 *
 * <p>Reads happen on Netty's event loops for every pack request, while writes happen
 * on whatever thread recompiled a pack. Rather than lock, the registry keeps an
 * immutable map behind an {@link AtomicReference}: a reader takes one volatile read
 * and then works on a snapshot that cannot change under it. A pack being replaced
 * mid-download therefore cannot affect the in-flight response -- the old
 * {@link PackSource} stays valid because it is just a path plus a hash, and the
 * compiler's atomic rename means the file it points at is either the old one or the
 * new one, never a half-written blend.
 *
 * <p>{@link #generation()} increments on every mutation. Callers that push packs to
 * clients use it to detect "the pack changed, re-push it" without diffing contents.
 */
public final class PackRegistry implements PackHttpRouter {

    private final AtomicReference<Map<String, PackSource>> snapshot =
        new AtomicReference<>(Map.of());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong registrations = new AtomicLong();
    private final AtomicLong replacements = new AtomicLong();
    private final boolean verifyOnRegister;

    public PackRegistry() {
        // Verifying a hash on registration means reading the whole archive. Worth it
        // when an operator registers a prebuilt pack they may have copied by hand, not
        // worth it immediately after our own compiler produced the file in the same
        // process.
        this(false);
    }

    public PackRegistry(boolean verifyOnRegister) {
        this.verifyOnRegister = verifyOnRegister;
    }

    /** Registers or replaces a pack. Returns the source that is now live. */
    public PackSource register(PackSource source) {
        Objects.requireNonNull(source, "source");
        if (!Files.isRegularFile(source.path())) {
            throw new IllegalArgumentException(
                "pack '" + source.id() + "' points at a missing file: " + source.path());
        }
        if (source.size() <= 0) {
            throw new IllegalArgumentException("pack '" + source.id() + "' is empty: " + source.path());
        }
        if (verifyOnRegister && !source.verifyIntegrity()) {
            throw new IllegalArgumentException(
                "pack '" + source.id() + "' failed its integrity check: " + source.path());
        }

        while (true) {
            Map<String, PackSource> current = snapshot.get();
            Map<String, PackSource> updated = new LinkedHashMap<>(current);
            PackSource previous = updated.put(source.id(), source);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                registrations.incrementAndGet();
                if (previous != null) {
                    replacements.incrementAndGet();
                }
                generation.incrementAndGet();
                return source;
            }
        }
    }

    /** Registers a just-compiled artifact. */
    public PackSource register(PackCompileResult result, String displayName) {
        return register(result.toPackSource(displayName));
    }

    /** Removes a pack. Returns the source that was removed, if any. */
    public Optional<PackSource> unregister(String packId) {
        while (true) {
            Map<String, PackSource> current = snapshot.get();
            if (!current.containsKey(packId)) {
                return Optional.empty();
            }
            Map<String, PackSource> updated = new LinkedHashMap<>(current);
            PackSource removed = updated.remove(packId);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                generation.incrementAndGet();
                return Optional.of(removed);
            }
        }
    }

    public void clear() {
        generation.incrementAndGet();
        snapshot.set(Map.of());
    }

    @Override
    public Optional<PackSource> resolve(String packId) {
        if (packId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get().get(packId));
    }

    @Override
    public Set<String> knownPackIds() {
        return snapshot.get().keySet();
    }

    /** Immutable view of everything currently registered. */
    public Map<String, PackSource> snapshot() {
        return snapshot.get();
    }

    /** True when at least one pack is registered. */
    public boolean isEmpty() {
        return snapshot.get().isEmpty();
    }

    public int size() {
        return snapshot.get().size();
    }

    /** Monotonic counter bumped on every change; use it to invalidate pushed packs. */
    public long generation() {
        return generation.get();
    }

    public long registrations() {
        return registrations.get();
    }

    public long replacements() {
        return replacements.get();
    }

    /** Total bytes the server would transfer for a client that took every pack once. */
    public long totalBytes() {
        long total = 0;
        for (PackSource source : snapshot.get().values()) {
            total += source.size();
        }
        return total;
    }

    public Map<String, String> describe() {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("packs", Integer.toString(size()));
        report.put("generation", Long.toString(generation.get()));
        report.put("replacements", Long.toString(replacements.get()));
        report.put("totalBytes", Long.toString(totalBytes()));
        report.put("packIds", String.join(",", knownPackIds()));
        return report;
    }
}
