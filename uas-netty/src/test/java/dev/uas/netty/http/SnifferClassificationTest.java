package dev.uas.netty.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.netty.http.HttpProtocolSniffer.Detection;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the classification rule directly, without sockets.
 *
 * <p>The socket tests prove the rule works end to end; these pin down the rule
 * itself, including the property that makes it safe: a real Minecraft handshake can
 * never be mistaken for HTTP.
 */
class SnifferClassificationTest {

    @Test
    @DisplayName("every supported method is classified as HTTP once complete")
    void fullMethodsAreHttp() {
        assertEquals(9, HttpProtocolSniffer.supportedHttpMethods().size());
        for (String method : HttpProtocolSniffer.supportedHttpMethods()) {
            String requestLine = method + " /uas/pack/v1 HTTP/1.1\r\n";
            assertEquals(Detection.HTTP, HttpProtocolSniffer.classify(requestLine.getBytes(StandardCharsets.US_ASCII)),
                method);
        }
    }

    @Test
    @DisplayName("a proper prefix of a method is undecided, never a wrong answer")
    void partialMethodsWait() {
        for (String method : HttpProtocolSniffer.supportedHttpMethods()) {
            byte[] token = (method + " ").getBytes(StandardCharsets.US_ASCII);
            for (int length = 1; length < token.length; length++) {
                byte[] prefix = java.util.Arrays.copyOf(token, length);
                // A prefix must either be undecided, or be a complete shorter method.
                Detection detection = HttpProtocolSniffer.classify(prefix);
                assertFalse(detection == Detection.MINECRAFT,
                    "prefix of " + method + " of length " + length + " must not be read as Minecraft");
            }
        }
    }

    @Test
    @DisplayName("no method token is a proper prefix of another")
    void noMethodIsAPrefixOfAnother() {
        var methods = HttpProtocolSniffer.supportedHttpMethods();
        for (String a : methods) {
            for (String b : methods) {
                if (a.equals(b)) {
                    continue;
                }
                assertFalse(b.startsWith(a), a + " is a prefix of " + b + ", which would be ambiguous");
            }
        }
    }

    @Test
    @DisplayName("no Minecraft handshake can be classified as HTTP")
    void minecraftHandshakesAreNeverHttp() {
        // Argument being tested: byte 0 is the handshake's packet-length VarInt
        // (0..127 for any plausible handshake), byte 1 is the packet id, always 0x00.
        // No HTTP method token has 0x00 in position 1.
        for (int lengthPrefix = 0; lengthPrefix <= 127; lengthPrefix++) {
            byte[] prefix = {(byte) lengthPrefix, 0x00, 0x00, 0x00};
            assertFalse(HttpProtocolSniffer.classify(prefix) == Detection.HTTP,
                "handshake length prefix " + lengthPrefix + " was misread as HTTP");
        }
        // And with only the length byte available, an ambiguous first byte must wait
        // rather than commit.
        assertEquals(Detection.UNDECIDED, HttpProtocolSniffer.classify(new byte[] {'P'}));
        assertEquals(Detection.UNDECIDED, HttpProtocolSniffer.classify(new byte[] {'H'}));
        assertEquals(Detection.MINECRAFT, HttpProtocolSniffer.classify(new byte[] {'P', 0x00}));
    }

    @Test
    @DisplayName("a legacy ping byte is always Minecraft")
    void legacyPing() {
        assertEquals(Detection.MINECRAFT, HttpProtocolSniffer.classify(new byte[] {(byte) 0xFE}));
        assertEquals(Detection.MINECRAFT, HttpProtocolSniffer.classify(new byte[] {(byte) 0xFE, 0x01}));
    }

    @Test
    @DisplayName("empty input is undecided rather than a guess")
    void emptyInput() {
        assertEquals(Detection.UNDECIDED, HttpProtocolSniffer.classify(null));
        assertEquals(Detection.UNDECIDED, HttpProtocolSniffer.classify(new byte[0]));
    }

    @Test
    @DisplayName("arbitrary binary input is never read as HTTP")
    void binaryIsNotHttp() {
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 20_000; i++) {
            byte[] sample = new byte[4 + random.nextInt(8)];
            random.nextBytes(sample);
            if (HttpProtocolSniffer.classify(sample) == Detection.HTTP) {
                // The only legitimate way to reach HTTP is a literal method token.
                String text = new String(sample, StandardCharsets.US_ASCII);
                boolean isRealMethod = HttpProtocolSniffer.supportedHttpMethods().stream()
                    .anyMatch(method -> text.startsWith(method + " "));
                assertTrue(isRealMethod, "unexpected HTTP classification for " + java.util.Arrays.toString(sample));
            }
        }
    }
}
