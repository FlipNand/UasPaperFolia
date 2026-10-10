package dev.uas.netty.http;

import dev.uas.api.pack.PackSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Resolves a pack id to a streamable artifact.
 *
 * <p>Kept as a tiny interface so the HTTP layer never depends on how packs are
 * produced. The immutable map implementation is what production uses: a pack is
 * published once and never mutated, which means the request path needs no locking
 * and cannot observe a half-written archive.
 */
public interface PackHttpRouter {

    Optional<PackSource> resolve(String packId);

    /** Ids currently routable, for diagnostics. */
    default Set<String> knownPackIds() {
        return Set.of();
    }

    static PackHttpRouter empty() {
        return new PackHttpRouter() {
            @Override
            public Optional<PackSource> resolve(String packId) {
                return Optional.empty();
            }
        };
    }

    /** A fixed routing table. */
    static PackHttpRouter of(Map<String, PackSource> packs) {
        Map<String, PackSource> snapshot = Map.copyOf(packs);
        return new PackHttpRouter() {
            @Override
            public Optional<PackSource> resolve(String packId) {
                return Optional.ofNullable(snapshot.get(packId));
            }

            @Override
            public Set<String> knownPackIds() {
                return snapshot.keySet();
            }
        };
    }

    /**
     * A live view over a mutable registry.
     *
     * <p>Snapshots the supplier's current contents per lookup, so a freshly
     * published pack becomes routable immediately without restarting the listener.
     * The supplier must return an immutable or copy-on-write snapshot.
     */
    static PackHttpRouter ofSupplier(Supplier<Collection<PackSource>> supplier) {
        return new PackHttpRouter() {
            @Override
            public Optional<PackSource> resolve(String packId) {
                for (PackSource source : supplier.get()) {
                    if (source.id().equals(packId)) {
                        return Optional.of(source);
                    }
                }
                return Optional.empty();
            }

            @Override
            public Set<String> knownPackIds() {
                Map<String, PackSource> byId = new LinkedHashMap<>();
                for (PackSource source : supplier.get()) {
                    byId.put(source.id(), source);
                }
                return byId.keySet();
            }
        };
    }
}
