package dev.uas.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.uas.netty.pipeline.PipelineNames;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.ResourceLeakDetector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the multiplexer through real loopback sockets.
 *
 * <p>Nothing here is mocked below the socket level: the test binds an actual
 * {@code ServerBootstrap}, the hook is installed on the real listening channel, and
 * connections go through Netty's acceptor exactly as they do inside Paper. That is
 * the only way to prove the hook timing (which is what makes the whole design work)
 * rather than assume it.
 */
@Timeout(60)
class MultiplexerIntegrationTest {

    private PackTestFixture fixture;
    private VanillaPipelineRecorder recorder;
    private AssetStreamServer server;

    @BeforeEach
    void setUp() throws Exception {
        fixture = PackTestFixture.create("v1");
        recorder = new VanillaPipelineRecorder();
        server = AssetStreamServer.builder()
            .port(0)
            .router(fixture.router())
            .vanillaPipeline(recorder::install)
            .hookTimeout(Duration.ofSeconds(5))
            .bind();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    // ------------------------------------------------------------------ plumbing

    @Test
    @DisplayName("the harness is armed: leak detection is at the most aggressive level")
    void leakDetectionIsParanoid() {
        assertEquals(ResourceLeakDetector.Level.PARANOID, ResourceLeakDetector.getLevel(),
            "tests must run with paranoid leak detection; check the Test task jvmArgs");
    }

    @Test
    @DisplayName("the child-channel hook is installed before the bootstrap acceptor")
    void hookIsInstalled() {
        assertTrue(server.isHooked(), "the server channel must carry the UAS child hook");
        assertTrue(server.vanillaPipeline() != null);
    }

    // ------------------------------------------------------- Minecraft pass-through

    @Test
    @DisplayName("a vanilla handshake reaches the vanilla pipeline byte-for-byte and the sniffer detaches")
    void minecraftHandshakePassesThroughUnmodified() throws Exception {
        byte[] handshake = handshakeWithBodyLength(80);
        byte[] followUp = followUpPacket();

        sendInTwoReads(handshake, followUp);

        assertArrayEquals(concatenate(handshake, followUp), recorder.capturedBytes(),
            "the sniffer must not alter a single byte of the Minecraft stream");
        // The classifying read is relayed by the sniffer, so it is still installed for
        // that one delivery. Everything after it must be free of the sniffer -- that is
        // the actual "no per-packet overhead" claim.
        assertEquals(1L, recorder.deliveriesSeeing(PipelineNames.MULTIPLEXER),
            "exactly one relayed delivery may observe the sniffer: " + recorder.pipelineSnapshots());
        assertFalse(recorder.lastPipelineSnapshot().contains(PipelineNames.MULTIPLEXER),
            "later reads must bypass the multiplexer entirely: " + recorder.lastPipelineSnapshot());
        assertEquals(1L, server.stats().minecraftConnections());
        assertEquals(0L, server.stats().httpConnections());
    }

    @Test
    @DisplayName("a handshake whose length prefix looks like an HTTP verb prefix is still classified correctly")
    void ambiguousLeadingByteIsResolvedByWaitingForMoreBytes() throws Exception {
        // Length prefix 80 == 'P', which is a prefix of POST / PUT / PATCH. The
        // sniffer must not commit on the first byte; the 0x00 packet id that follows
        // is what proves this is Minecraft.
        byte[] handshake = handshakeWithBodyLength(80);
        assertEquals('P', handshake[0] & 0xFF, "fixture must start with an ambiguous byte");
        byte[] followUp = followUpPacket();

        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(handshake, 0, 1);
            out.flush();
            Thread.sleep(120);
            assertEquals(0, recorder.receivedBytes(),
                "the sniffer must hold an ambiguous prefix instead of guessing");

            out.write(handshake, 1, handshake.length - 1);
            out.flush();
            assertTrue(recorder.awaitBytes(handshake.length, 5_000));

            Thread.sleep(120);
            out.write(followUp);
            out.flush();
            assertTrue(recorder.awaitBytes(handshake.length + followUp.length, 5_000));
        }

        assertArrayEquals(concatenate(handshake, followUp), recorder.capturedBytes());
        assertTrue(server.stats().ambiguousBytes() > 0, "the ambiguous path must have been taken");
        assertFalse(recorder.lastPipelineSnapshot().contains(PipelineNames.MULTIPLEXER));
    }

    @Test
    @DisplayName("a handshake delivered one byte at a time is reassembled intact")
    void byteAtATimeHandshake() throws Exception {
        byte[] handshake = handshakeWithBodyLength(80);
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            for (byte value : handshake) {
                out.write(value);
                out.flush();
                Thread.sleep(2);
            }
            assertTrue(recorder.awaitBytes(handshake.length, 10_000),
                "saw " + recorder.receivedBytes() + " of " + handshake.length);
        }
        assertArrayEquals(handshake, recorder.capturedBytes());
    }

    @Test
    @DisplayName("a legacy server-list ping passes straight through")
    void legacyPingPassesThrough() throws Exception {
        byte[] followUp = followUpPacket();
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(0xFE);
            out.flush();
            assertTrue(recorder.awaitBytes(1, 5_000));
            Thread.sleep(120);
            out.write(followUp);
            out.flush();
            assertTrue(recorder.awaitBytes(1 + followUp.length, 5_000));
        }
        assertArrayEquals(concatenate(new byte[] {(byte) 0xFE}, followUp), recorder.capturedBytes());
        assertFalse(recorder.lastPipelineSnapshot().contains(PipelineNames.MULTIPLEXER));
        assertEquals(0L, server.stats().httpConnections());
    }

    @Test
    @DisplayName("both protocols work on separate connections to the same port")
    void mixedTrafficOnOnePort() throws Exception {
        byte[] handshake = handshakeWithBodyLength(80);
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshake);
            socket.getOutputStream().flush();
            assertTrue(recorder.awaitBytes(handshake.length, 5_000));
        }
        assertArrayEquals(handshake, recorder.capturedBytes());

        HttpResponse<byte[]> response = get(packUri());
        assertEquals(200, response.statusCode());
        assertArrayEquals(fixture.readArchiveBytes(), response.body());
        assertEquals(1L, server.stats().minecraftConnections());
        assertEquals(1L, server.stats().httpConnections());
    }

    // -------------------------------------------------------------- HTTP delivery

    @Test
    @DisplayName("GET /uas/pack/{id} streams the exact archive with cache headers")
    void getServesExactBytesWithHeaders() throws Exception {
        HttpResponse<byte[]> response = get(packUri());
        assertEquals(200, response.statusCode());
        assertArrayEquals(fixture.readArchiveBytes(), response.body(),
            "the streamed body must be byte-identical to the file on disk");
        assertEquals(Long.toString(fixture.source().size()),
            header(response, HttpHeaderNames.CONTENT_LENGTH.toString()));
        assertEquals("\"" + fixture.sha1() + "\"", header(response, HttpHeaderNames.ETAG.toString()));
        assertEquals("public, max-age=31536000, immutable",
            header(response, HttpHeaderNames.CACHE_CONTROL.toString()));
        assertEquals("bytes", header(response, HttpHeaderNames.ACCEPT_RANGES.toString()));
        assertEquals("application/zip", header(response, HttpHeaderNames.CONTENT_TYPE.toString()));
        assertNotNull(header(response, HttpHeaderNames.DATE.toString()));
    }

    @Test
    @DisplayName("HEAD reports the length with no body")
    void headHasNoBody() throws Exception {
        HttpResponse<byte[]> response = client().send(
            HttpRequest.newBuilder(packUri()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals(Long.toString(fixture.source().size()),
            header(response, HttpHeaderNames.CONTENT_LENGTH.toString()));
    }

    @Test
    @DisplayName("If-None-Match returns 304 with no body")
    void conditionalRequestReturnsNotModified() throws Exception {
        HttpResponse<byte[]> response = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.IF_NONE_MATCH.toString(), "\"" + fixture.sha1() + "\"")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("\"" + fixture.sha1() + "\"", header(response, HttpHeaderNames.ETAG.toString()));
    }

    @Test
    @DisplayName("a stale If-None-Match falls through to a full 200")
    void staleConditionalRequestServesFullBody() throws Exception {
        HttpResponse<byte[]> response = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.IF_NONE_MATCH.toString(), "\"0000000000000000000000000000000000000000\"")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(fixture.readArchiveBytes(), response.body());
    }

    @Test
    @DisplayName("a Range request returns 206 with the exact slice and Content-Range")
    void rangeRequestReturnsExactSlice() throws Exception {
        byte[] whole = fixture.readArchiveBytes();
        long start = 70_000;
        long end = 142_337;
        HttpResponse<byte[]> response = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.RANGE.toString(), "bytes=" + start + "-" + end)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, response.statusCode());
        assertEquals("bytes " + start + "-" + end + "/" + whole.length,
            header(response, HttpHeaderNames.CONTENT_RANGE.toString()));
        assertEquals(Long.toString(end - start + 1), header(response, HttpHeaderNames.CONTENT_LENGTH.toString()));
        assertArrayEquals(Arrays.copyOfRange(whole, (int) start, (int) end + 1), response.body());
    }

    @Test
    @DisplayName("suffix ranges, open-ended ranges and multi-range reassembly all line up")
    void rangeVariantsAndReassembly() throws Exception {
        byte[] whole = fixture.readArchiveBytes();
        long size = whole.length;

        HttpResponse<byte[]> suffix = client().send(
            HttpRequest.newBuilder(packUri()).header(HttpHeaderNames.RANGE.toString(), "bytes=-1024").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, suffix.statusCode());
        assertArrayEquals(Arrays.copyOfRange(whole, (int) size - 1024, (int) size), suffix.body());

        HttpResponse<byte[]> openEnded = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.RANGE.toString(), "bytes=" + (size - 500) + "-")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, openEnded.statusCode());
        assertArrayEquals(Arrays.copyOfRange(whole, (int) size - 500, (int) size), openEnded.body());

        // Download in three pieces and verify the concatenation is the whole file:
        // this is exactly what a resumable client does after a dropped connection.
        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        long cursor = 0;
        long[] boundaries = {0, size / 3, (size / 3) * 2, size};
        for (int i = 1; i < boundaries.length; i++) {
            long from = boundaries[i - 1];
            long to = boundaries[i] - 1;
            HttpResponse<byte[]> part = client().send(
                HttpRequest.newBuilder(packUri())
                    .header(HttpHeaderNames.RANGE.toString(), "bytes=" + from + "-" + to)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, part.statusCode(), "part " + from + "-" + to);
            assertEquals(to - from + 1, part.body().length);
            reassembled.write(part.body());
            cursor += part.body().length;
        }
        assertEquals(size, cursor);
        assertArrayEquals(whole, reassembled.toByteArray());

        // A multi-range header must be answered with the first range only.
        HttpResponse<byte[]> multi = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.RANGE.toString(), "bytes=0-99,200-299")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, multi.statusCode());
        assertEquals(100, multi.body().length);
        assertArrayEquals(Arrays.copyOfRange(whole, 0, 100), multi.body());
    }

    @Test
    @DisplayName("an unsatisfiable range returns 416 with bytes */length")
    void unsatisfiableRangeReturns416() throws Exception {
        HttpResponse<byte[]> response = client().send(
            HttpRequest.newBuilder(packUri())
                .header(HttpHeaderNames.RANGE.toString(), "bytes=" + (fixture.source().size() + 10) + "-")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(416, response.statusCode());
        assertEquals("bytes */" + fixture.source().size(),
            header(response, HttpHeaderNames.CONTENT_RANGE.toString()));
    }

    @Test
    @DisplayName("errors and diagnostics endpoints behave")
    void errorAndDiagnosticRoutes() throws Exception {
        assertEquals(404, get(base().resolve("/uas/pack/does-not-exist")).statusCode());
        assertEquals(404, get(base().resolve("/nope")).statusCode());
        assertEquals(404, get(base().resolve("/uas/pack/")).statusCode());

        HttpResponse<byte[]> ping = get(base().resolve("/uas/ping"));
        assertEquals(200, ping.statusCode());
        assertEquals("uas/1\n", new String(ping.body(), StandardCharsets.UTF_8));

        HttpResponse<byte[]> packs = get(base().resolve("/uas/packs"));
        assertEquals(200, packs.statusCode());
        String json = new String(packs.body(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"id\":\"v1\""), json);
        assertTrue(json.contains(fixture.sha1()), json);

        HttpResponse<byte[]> post = client().send(
            HttpRequest.newBuilder(packUri()).POST(HttpRequest.BodyPublishers.ofString("x")).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", header(post, HttpHeaderNames.ALLOW.toString()));
    }

    @Test
    @DisplayName("keep-alive reuses one HTTP connection for several requests")
    void keepAliveServesSeveralRequests() throws Exception {
        HttpClient client = client();
        for (int i = 0; i < 3; i++) {
            HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(packUri()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(fixture.readArchiveBytes(), response.body());
        }
        // All three requests must have arrived over the same classification.
        assertEquals(0L, server.stats().minecraftConnections());
    }

    // ------------------------------------------------------ reference-count hygiene

    @Test
    @DisplayName("every relayed buffer arrives with exactly one reference and is fully released")
    void relayedBuffersHaveExactlyOneReference() throws Exception {
        byte[] handshake = handshakeWithBodyLength(80);
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshake);
            socket.getOutputStream().flush();
            assertTrue(recorder.awaitBytes(handshake.length, 5_000));
        }
        for (int i = 0; i < 5; i++) {
            assertEquals(200, get(packUri()).statusCode());
        }

        assertFalse(recorder.arrivalRefCounts().isEmpty());
        for (int refCount : recorder.arrivalRefCounts()) {
            assertEquals(1, refCount,
                "downstream must receive sole ownership of each relayed buffer");
        }
        for (var buffer : recorder.arrivedBuffers()) {
            assertEquals(0, buffer.refCnt(),
                "each buffer must be fully released once the vanilla pipeline is done with it");
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Sends two payloads as two distinct server-side reads.
     *
     * <p>The pause is what makes the detach assertion meaningful: without it the two
     * writes can coalesce into one segment, and then there is no second read on which
     * to observe that the sniffer is gone.
     */
    private void sendInTwoReads(byte[] first, byte[] second) throws IOException, InterruptedException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(first);
            out.flush();
            assertTrue(recorder.awaitBytes(first.length, 5_000),
                "expected " + first.length + " bytes, saw " + recorder.receivedBytes());
            Thread.sleep(150);
            out.write(second);
            out.flush();
            assertTrue(recorder.awaitBytes(first.length + second.length, 5_000),
                "expected " + (first.length + second.length) + " bytes, saw " + recorder.receivedBytes());
        }
    }

    /** Bytes resembling a second framed packet; only identity matters to the pipeline. */
    private static byte[] followUpPacket() {
        return new byte[] {0x03, 0x00, 0x11, 0x22};
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout(5_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private URI packUri() {
        return base().resolve("/uas/pack/v1");
    }

    private HttpResponse<byte[]> get(URI uri) throws Exception {
        return client().send(
            HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /**
     * Builds a real vanilla handshake packet whose declared body length is
     * {@code bodyLength}, so the leading byte can be chosen deliberately.
     */
    private static byte[] handshakeWithBodyLength(int bodyLength) {
        int protocolVersion = 767;
        int protocolBytes = varIntSize(protocolVersion);
        // body = id(1) + protocol + hostLenVarInt(1) + host + port(2) + nextState(1)
        int hostLength = bodyLength - 1 - protocolBytes - 1 - 2 - 1;
        if (hostLength < 1 || hostLength > 120) {
            throw new IllegalArgumentException("body length " + bodyLength + " yields host length " + hostLength);
        }
        String host = "a".repeat(hostLength);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        writeVarInt(body, protocolVersion);
        writeVarInt(body, hostLength);
        body.writeBytes(host.getBytes(StandardCharsets.UTF_8));
        body.write(0x63);
        body.write((byte) 0xDD);
        writeVarInt(body, 2);

        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, body.size());
        packet.writeBytes(body.toByteArray());
        return packet.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        do {
            int bits = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                bits |= 0x80;
            }
            out.write(bits);
        } while (remaining != 0);
    }

    private static int varIntSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }
}
