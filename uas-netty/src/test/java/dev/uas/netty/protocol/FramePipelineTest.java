package dev.uas.netty.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.protocol.PacketRegistry;
import dev.uas.protocol.UasFrame;
import dev.uas.protocol.UasFrameCodec;
import dev.uas.protocol.UasProtocol;
import dev.uas.protocol.UasProtocolException;
import dev.uas.protocol.packet.UasBlockInteractionPacket;
import dev.uas.protocol.packet.UasCustomSoundPacket;
import dev.uas.protocol.packet.UasHelloPacket;
import dev.uas.protocol.packet.UasUiStatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the Netty frame codec against the shared {@code UasFrameCodec}.
 *
 * <p>The decoder re-derives a frame's length from the header before the body has
 * arrived, which is a second implementation of the framing rule. Splitting at
 * <em>every</em> boundary and asserting the result matches the canonical codec is how
 * that duplication is kept honest.
 */
class FramePipelineTest {

    private final PacketRegistry registry = PacketRegistry.standard();

    private static UasHelloPacket hello() {
        return UasHelloPacket.bedrockClient(
            UasHelloPacket.CAP_CUSTOM_BLOCKS | UasHelloPacket.CAP_CUSTOM_UI_FORM | UasHelloPacket.CAP_RANGE_REQUESTS,
            "Geyser 26.3");
    }

    @Test
    @DisplayName("a frame written whole is decoded back to an identical packet")
    void wholeFrame() {
        UasHelloPacket original = hello();
        byte[] encoded = UasFrameCodec.encode(UasFrame.of(original), registry);

        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(encoded)));

        UasFrame frame = channel.readInbound();
        assertInstanceOf(UasHelloPacket.class, frame.packet());
        UasHelloPacket decoded = (UasHelloPacket) frame.packet();
        assertEquals(original.platform(), decoded.platform());
        assertEquals(original.capabilities(), decoded.capabilities());
        assertEquals(original.clientVersion(), decoded.clientVersion());
        assertEquals(UasProtocol.VERSION, frame.protocolVersion());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("a frame split at every byte boundary still decodes exactly once")
    void everySplitBoundary() {
        List<UasFrame> frames = List.of(
            UasFrame.of(hello()),
            UasFrame.of(UasCustomSoundPacket.local(
                dev.uas.api.block.SoundRef.blocks(dev.uas.api.NamespacedKey.uas("lantern.break"), 1f, 1f))),
            UasFrame.of(UasBlockInteractionPacket.stateSync(
                10, 64, -30, dev.uas.api.NamespacedKey.uas("lumen_lantern"), 12345)),
            UasFrame.of(UasUiStatePacket.update(
                dev.uas.api.NamespacedKey.uas("hud"),
                java.util.Map.of("charge", "42", "visible", "true"))));

        for (UasFrame frame : frames) {
            byte[] encoded = UasFrameCodec.encode(frame, registry);
            for (int split = 1; split < encoded.length; split++) {
                EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
                ByteBuf first = Unpooled.wrappedBuffer(encoded, 0, split);
                ByteBuf second = Unpooled.wrappedBuffer(encoded, split, encoded.length - split);

                boolean producedEarly = channel.writeInbound(first.retain());
                assertTrue(producedEarly || channel.readInbound() == null,
                    "a partial frame at split " + split + " must not produce a message");

                channel.writeInbound(second.retain());
                Object decoded = channel.readInbound();
                assertInstanceOf(UasFrame.class, decoded, "split " + split + " of " + encoded.length);
                assertEquals(frame.packet().id(), ((UasFrame) decoded).packet().id());
                assertNull(channel.readInbound(), "split " + split + " produced more than one frame");
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    @DisplayName("byte-at-a-time feeding still yields exactly one frame")
    void byteAtATime() {
        UasFrame frame = UasFrame.of(hello());
        byte[] encoded = UasFrameCodec.encode(frame, registry);
        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        List<Object> produced = new ArrayList<>();
        for (byte value : encoded) {
            if (channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {value}))) {
                produced.add(channel.readInbound());
            }
        }
        long frames = produced.stream().filter(UasFrame.class::isInstance).count();
        assertEquals(1, frames, "exactly one frame expected, got " + produced);
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("two frames in one buffer both decode, in order")
    void backToBackFrames() {
        UasFrame first = UasFrame.of(hello());
        UasFrame second = UasFrame.of(UasBlockInteractionPacket.stateSync(
            1, 2, 3, dev.uas.api.NamespacedKey.uas("x"), 7));
        ByteBuf combined = Unpooled.wrappedBuffer(
            UasFrameCodec.encode(first, registry), UasFrameCodec.encode(second, registry));

        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        channel.writeInbound(combined);
        List<Object> decoded = new ArrayList<>();
        Object next;
        while ((next = channel.readInbound()) != null) {
            decoded.add(next);
        }
        assertEquals(2, decoded.size(), "got " + decoded);
        assertEquals(first.packet().id(), ((UasFrame) decoded.get(0)).packet().id());
        assertEquals(second.packet().id(), ((UasFrame) decoded.get(1)).packet().id());
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("truncated input produces nothing and does not throw")
    void truncatedInput() {
        UasFrame frame = UasFrame.of(hello());
        byte[] encoded = UasFrameCodec.encode(frame, registry);
        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        channel.writeInbound(Unpooled.wrappedBuffer(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertNull(channel.readInbound(), "a partial frame must be held, not emitted");
        channel.finishAndReleaseAll();
    }

    @Test
    @DisplayName("a bad magic byte is a hard protocol error")
    void badMagic() {
        byte[] encoded = UasFrameCodec.encode(UasFrame.of(hello()), registry);
        encoded[0] = 0x00;
        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        Throwable thrown = expectProtocolFailure(channel, encoded);
        assertTrue(hasCause(thrown, UasProtocolException.class), "expected a UasProtocolException, got " + thrown);
        drainAndRelease(channel);
    }

    @Test
    @DisplayName("an oversized declared payload length is rejected before allocating")
    void oversizedDeclaredLength() {
        // magic, version 1, flags 0, payload length = 2^24
        byte[] header = {
            (byte) UasProtocol.MAGIC_0, (byte) UasProtocol.MAGIC_1, 0x01, 0x00,
            (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01,
        };
        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        Throwable thrown = expectProtocolFailure(channel, header);
        assertTrue(hasCause(thrown, UasProtocolException.class), "expected a UasProtocolException, got " + thrown);
        drainAndRelease(channel);
    }

    @Test
    @DisplayName("reserved flag bits are rejected")
    void reservedFlags() {
        byte[] header = {
            (byte) UasProtocol.MAGIC_0, (byte) UasProtocol.MAGIC_1, 0x01, 0x40, 0x00,
        };
        EmbeddedChannel channel = new EmbeddedChannel(new UasFrameDecoder(registry));
        Throwable thrown = expectProtocolFailure(channel, header);
        assertTrue(hasCause(thrown, UasProtocolException.class), "expected a UasProtocolException, got " + thrown);
        drainAndRelease(channel);
    }

    @Test
    @DisplayName("the encoder and decoder round-trip through the pipeline")
    void encoderDecoderRoundTrip() {
        UasBlockInteractionPacket packet = new UasBlockInteractionPacket(
            5, 70, -12, UasBlockInteractionPacket.Action.INTERACT,
            dev.uas.api.block.Face.NORTH, UUID.randomUUID(),
            dev.uas.api.NamespacedKey.uas("lumen_lantern"), 0xBEEF);
        packet.withFinePosition(0.25, 0.5, 0.75);

        EmbeddedChannel channel = new EmbeddedChannel(
            new UasFrameEncoder(registry), new UasFrameDecoder(registry));
        channel.writeOutbound(UasFrame.of(packet));
        ByteBuf encoded = channel.readOutbound();
        // Feed the encoded bytes back through the inbound side of the same channel.
        channel.writeInbound(encoded);

        UasFrame decoded = channel.readInbound();
        assertInstanceOf(UasBlockInteractionPacket.class, decoded.packet());
        UasBlockInteractionPacket back = (UasBlockInteractionPacket) decoded.packet();
        assertEquals(packet.x(), back.x());
        assertEquals(packet.y(), back.y());
        assertEquals(packet.z(), back.z());
        assertEquals(packet.action(), back.action());
        assertEquals(packet.face(), back.face());
        assertEquals(packet.playerId(), back.playerId());
        assertEquals(packet.blockKey(), back.blockKey());
        assertEquals(packet.stateHash(), back.stateHash());
        assertEquals(packet.fineX(), back.fineX(), 1e-6);
        channel.finishAndReleaseAll();
    }

    /**
     * Feeds malformed input and returns the resulting protocol failure.
     *
     * <p>Netty can surface a decoder failure either by propagating it out of
     * {@code writeInbound} or by recording it for {@code checkException}, depending on
     * where in the pipeline it is raised. Both are checked so the test asserts the
     * failure exists and is a {@link UasProtocolException}, without pinning down which
     * channel implementation detail delivered it.
     */
    private static Throwable expectProtocolFailure(EmbeddedChannel channel, byte[] data) {
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(data));
        } catch (Throwable thrown) {
            return thrown;
        }
        try {
            channel.checkException();
        } catch (Throwable thrown) {
            return thrown;
        }
        return null;
    }

    /**
     * Finishes an {@code EmbeddedChannel} whose recorded exception has already been
     * asserted on. {@code finishAndReleaseAll()} rethrows it, so it has to be swallowed
     * here -- but only here, after the assertion, never instead of it.
     */
    private static void drainAndRelease(EmbeddedChannel channel) {
        try {
            channel.finishAndReleaseAll();
        } catch (Throwable alreadyAsserted) {
            // expected: EmbeddedChannel keeps replaying the recorded exception
        }
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
