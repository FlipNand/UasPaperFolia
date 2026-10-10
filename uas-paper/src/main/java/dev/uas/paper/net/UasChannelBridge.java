package dev.uas.paper.net;

import dev.uas.paper.platform.PlayerWorkQueue;
import dev.uas.protocol.CustomPacketCodec;
import dev.uas.protocol.PacketRegistry;
import dev.uas.protocol.UasFrame;
import dev.uas.protocol.UasPacket;
import dev.uas.protocol.UasProtocol;
import dev.uas.protocol.packet.UasHelloPacket;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * The Java side of the shared custom-packet channel.
 *
 * <p>One channel, {@code uas:channel}, carrying the engine's own frame format over
 * vanilla plugin messages. That choice is what makes the transport genuinely
 * cross-platform: the same bytes arrive from a Java client (a companion mod reading
 * plugin messages) and from a Bedrock client translated by the Geyser bridge, so the
 * protocol has exactly one encoder and one decoder rather than a Java path and a
 * Bedrock path that drift apart.
 *
 * <h2>Three properties this enforces</h2>
 *
 * <ul>
 *   <li><strong>Untrusted input is validated before it is decoded.</strong> A plugin
 *       message is client-controlled. The frame is length- and magic-checked first, so a
 *       hostile client cannot make the decoder allocate or run off the end of the
 *       buffer, and a decode failure is a dropped message, never an exception that
 *       propagates into the server's message handling.</li>
 *   <li><strong>Nothing is dispatched on the wrong thread.</strong> Receiving a plugin
 *       message can happen on a Netty thread; listeners are called on the player's
 *       owning thread (region thread on Folia), so a listener can safely touch the
 *       player, their inventory or their world without knowing which platform it is on.</li>
 *   <li><strong>Outgoing sends are queued, not sent inline.</strong> Sending to an entity
 *       requires its owning thread on Folia, and routing through the same coalescing
 *       queue as everything else means a burst of HUD updates collapses into one task.</li>
 * </ul>
 */
public final class UasChannelBridge implements PluginMessageListener {

    private static final Logger LOGGER = Logger.getLogger(UasChannelBridge.class.getName());

    private final Plugin plugin;
    private final CustomPacketCodec codec;
    private final PlayerWorkQueue workQueue;
    private final List<java.util.function.Consumer<Inbound>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();

    private volatile boolean registered;

    public UasChannelBridge(Plugin plugin, PacketRegistry registry, PlayerWorkQueue workQueue) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        // The codec owns the encode/decode composition so the bridge never has to sequence
        // framing, versioning and registry lookup itself.
        this.codec = new CustomPacketCodec(Objects.requireNonNull(registry, "registry"));
        this.workQueue = Objects.requireNonNull(workQueue, "workQueue");
    }

    /** A decoded frame attributed to the player that sent it. */
    public record Inbound(UUID playerId, UasFrame frame) {

        public Inbound {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(frame, "frame");
        }
    }

    /** Registers both directions of the channel. Idempotent. */
    public void register() {
        if (registered) {
            return;
        }
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, UasProtocol.CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, UasProtocol.CHANNEL);
        registered = true;
        LOGGER.info("UAS registered the " + UasProtocol.CHANNEL + " custom-packet channel");
    }

    /** Unregisters both directions. Safe to call when not registered. */
    public void unregister() {
        if (!registered) {
            return;
        }
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, UasProtocol.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, UasProtocol.CHANNEL);
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** Adds a listener; called on the sending player's owning thread. */
    public void addListener(java.util.function.Consumer<Inbound> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(java.util.function.Consumer<Inbound> listener) {
        listeners.remove(listener);
    }

    /**
     * The vanilla entry point. Never throws: a malformed message from a client must not
     * be able to break the server's plugin-message dispatch.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!UasProtocol.CHANNEL.equals(channel) || message == null || message.length == 0) {
            return;
        }
        // Untrusted, client-controlled bytes. The codec validates the marker and length before
        // it decodes anything, and reports failure as an empty result -- a hostile client must
        // not be able to raise an exception through the server's message dispatch.
        java.util.Optional<UasFrame> decoded = codec.tryDecode(message);
        if (decoded.isEmpty()) {
            rejected.incrementAndGet();
            LOGGER.log(Level.FINE, () -> "dropped a " + channel
                + " message from " + player.getName() + " that is not a valid UAS frame");
            return;
        }
        UasFrame frame = decoded.get();
        received.incrementAndGet();

        Inbound inbound = new Inbound(player.getUniqueId(), frame);
        if (listeners.isEmpty()) {
            return;
        }
        // Hand off to the player's owning thread: listeners are allowed to touch the
        // player and the world, which is only legal on that thread.
        workQueue.submit(player, () -> {
            for (java.util.function.Consumer<Inbound> listener : listeners) {
                try {
                    listener.accept(inbound);
                } catch (RuntimeException failure) {
                    LOGGER.log(Level.WARNING,
                        "a UAS packet listener failed for " + player.getName(), failure);
                }
            }
        });
    }

    /**
     * Sends a packet to one player, on that player's owning thread.
     *
     * @return true when the send was queued (not necessarily delivered)
     */
    public boolean send(Player player, UasPacket packet) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(packet, "packet");
        byte[] encoded;
        try {
            encoded = codec.encode(packet);
        } catch (RuntimeException oversized) {
            // Encoding failures are server bugs, not client ones: report them.
            LOGGER.log(Level.WARNING, "could not encode UAS packet " + packet.id(), oversized);
            return false;
        }
        if (!player.isOnline()) {
            rejected.incrementAndGet();
            return false;
        }
        workQueue.submit(player, () -> {
            player.sendPluginMessage(plugin, UasProtocol.CHANNEL, encoded);
            sent.incrementAndGet();
        });
        return true;
    }

    /** Sends the capability handshake so a client can adapt its rendering. */
    public boolean sendHello(Player player, long capabilities, String serverVersion) {
        return send(player, UasHelloPacket.server(capabilities, serverVersion));
    }

    /**
     * Capabilities the server offers. The bits mirror the protocol's own capability
     * constants, so a client can feature-detect without knowing which engine build it
     * is talking to.
     */
    public static long serverCapabilities() {
        return UasHelloPacket.CAP_ASSET_STREAMING
            | UasHelloPacket.CAP_CUSTOM_BLOCKS
            | UasHelloPacket.CAP_CUSTOM_SOUNDS
            | UasHelloPacket.CAP_CUSTOM_UI_OVERLAY
            | UasHelloPacket.CAP_CUSTOM_UI_FORM
            | UasHelloPacket.CAP_RANGE_REQUESTS
            | UasHelloPacket.CAP_BLOCK_DISPLAY_BACKEND
            | UasHelloPacket.CAP_NOTE_BLOCK_BACKEND;
    }

    public long received() {
        return received.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long sent() {
        return sent.get();
    }

    public java.util.Map<String, String> describe() {
        java.util.Map<String, String> report = new java.util.LinkedHashMap<>();
        report.put("channel.name", UasProtocol.CHANNEL);
        report.put("channel.registered", Boolean.toString(registered));
        report.put("channel.framesReceived", Long.toString(received.get()));
        report.put("channel.framesSent", Long.toString(sent.get()));
        report.put("channel.framesRejected", Long.toString(rejected.get()));
        report.put("channel.listeners", Integer.toString(listeners.size()));
        report.put("channel.helloCapabilities", Long.toString(serverCapabilities()));
        return report;
    }
}
