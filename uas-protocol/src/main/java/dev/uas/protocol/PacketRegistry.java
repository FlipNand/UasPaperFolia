package dev.uas.protocol;

import dev.uas.protocol.packet.UasBlockInteractionPacket;
import dev.uas.protocol.packet.UasCustomSoundPacket;
import dev.uas.protocol.packet.UasHelloPacket;
import dev.uas.protocol.packet.UasUiStatePacket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Maps wire ids to packet factories.
 *
 * <p>Lookup is by id with an optional name lookup for diagnostics. Unknown ids are
 * rejected with {@link UasProtocolException} rather than being skipped, because a
 * mismatched protocol version must fail loudly on both sides.
 */
public final class PacketRegistry {

    /** A registered packet: its id, its diagnostic name and how to instantiate it. */
    public record Type<T extends UasPacket>(int id, String name, Supplier<T> factory) {
        public Type {
            if (id < 0) {
                throw new IllegalArgumentException("packet id must be >= 0, got " + id);
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(factory, "factory");
        }

        public T create() {
            T packet = factory.get();
            if (packet.id() != id) {
                throw new IllegalStateException(
                    "packet type '" + name + "' declares id " + id + " but its instance reports " + packet.id());
            }
            return packet;
        }
    }

    private final Map<Integer, Type<? extends UasPacket>> byId = new LinkedHashMap<>();
    private final Map<String, Type<? extends UasPacket>> byName = new LinkedHashMap<>();

    public static PacketRegistry standard() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(new Type<>(UasHelloPacket.ID, "uas:hello", UasHelloPacket::new));
        registry.register(new Type<>(UasCustomSoundPacket.ID, "uas:sound", UasCustomSoundPacket::new));
        registry.register(new Type<>(UasUiStatePacket.ID, "uas:ui_state", UasUiStatePacket::new));
        registry.register(new Type<>(UasBlockInteractionPacket.ID, "uas:block_interaction",
            UasBlockInteractionPacket::new));
        return registry;
    }

    public synchronized PacketRegistry register(Type<? extends UasPacket> type) {
        Objects.requireNonNull(type, "type");
        Type<? extends UasPacket> existing = byId.get(type.id());
        if (existing != null) {
            throw new IllegalArgumentException(
                "packet id " + type.id() + " is already registered to '" + existing.name() + "'");
        }
        if (byName.containsKey(type.name())) {
            throw new IllegalArgumentException("packet name '" + type.name() + "' is already registered");
        }
        byId.put(type.id(), type);
        byName.put(type.name(), type);
        return this;
    }

    /** Instantiates a packet for a wire id. */
    public UasPacket create(int id) {
        Type<? extends UasPacket> type = byId.get(id);
        if (type == null) {
            throw new UasProtocolException(
                "unknown packet id " + id + "; known ids: " + byId.keySet());
        }
        return type.create();
    }

    public Optional<Type<? extends UasPacket>> type(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Type<? extends UasPacket>> type(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<Integer> knownIds() {
        return Set.copyOf(byId.keySet());
    }

    public int size() {
        return byId.size();
    }
}
