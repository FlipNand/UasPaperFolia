package dev.uas.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Diagnostic probe: sends hand-written HTTP/1.1 requests over raw sockets and
 * records exactly what the server writes back.
 *
 * <p>Kept in the suite because it is the only view of the multiplexer that is not
 * filtered through an HTTP client's own parser, which makes it the fastest way to
 * see a malformed response. It asserts too, so it cannot rot into a decorative
 * dump: the response must parse, the status must be 200 and the body length must
 * match the promised value.
 */
@Timeout(60)
class RawHttpProbeTest {

    private static final Path DUMP = Path.of("build", "uas-http-dump.txt");

    /** Verbose server-side logging is opt-in; the raw dump is always written. */
    private static void attachLogging() {
        if (!Boolean.getBoolean("uas.test.dumpLogs")) {
            return;
        }
        try {
            java.util.logging.FileHandler handler =
                new java.util.logging.FileHandler("build/uas-server.log", false);
            handler.setLevel(java.util.logging.Level.ALL);
            handler.setFormatter(new java.util.logging.SimpleFormatter());
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.setLevel(java.util.logging.Level.ALL);
            root.addHandler(handler);
            java.util.logging.Logger.getLogger("dev.uas").setLevel(java.util.logging.Level.FINEST);
        } catch (Exception e) {
            throw new IllegalStateException("could not attach the diagnostic log handler", e);
        }
    }

    @Test
    void dumpRawResponse() throws Exception {
        attachLogging();
        PackTestFixture fixture = PackTestFixture.create("v1", 4096);
        VanillaPipelineRecorder recorder = new VanillaPipelineRecorder();
        StringBuilder report = new StringBuilder();
        try (AssetStreamServer server = AssetStreamServer.builder()
            .port(0)
            .router(fixture.router())
            .vanillaPipeline(recorder::install)
            .hookTimeout(Duration.ofSeconds(5))
            .bind()) {

            report.append("bind=").append(server.bindAddress()).append('\n');
            report.append("hooked=").append(server.isHooked()).append('\n');

            record Probe(String request, int expectedStatus, int expectedBodyBytes) {
            }
            Probe[] probes = {
                new Probe("GET /uas/ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n", 200, 6),
                new Probe("GET /uas/packs HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n", 200, -1),
                new Probe("GET /uas/pack/v1 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
                    200, 4096),
                new Probe("GET /uas/pack/missing HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
                    404, -1),
            };

            for (Probe probe : probes) {
                String response = probe(server.port(), probe.request());
                report.append("\n======== REQUEST ========\n").append(probe.request().replace("\r\n", "\\n\n"));
                report.append("-------- RESPONSE (")
                    .append(response.length())
                    .append(" bytes) --------\n")
                    .append(response.replace("\r\n", "\\n\n"))
                    .append('\n');

                String statusLine = response.lines().findFirst()
                    .orElseThrow(() -> new AssertionError("no status line in: " + response));
                assertTrue(statusLine.startsWith("HTTP/1.1 " + probe.expectedStatus() + " "),
                    "unexpected status line: " + statusLine);
                String decoded = new String(
                    response.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
                int split = decoded.indexOf("\r\n\r\n");
                if (probe.expectedBodyBytes() >= 0) {
                    int bodyBytes = decoded.length() - split - 4;
                    assertEquals(probe.expectedBodyBytes(), bodyBytes,
                        "body length for " + probe.request().split(" ")[1]);
                }
            }
            report.append("\nstats=").append(server.stats()).append('\n');
            report.append("vanillaReceived=").append(recorder.receivedBytes()).append('\n');
        } finally {
            fixture.close();
        }
        Files.createDirectories(DUMP.getParent());
        Files.writeString(DUMP, report.toString());
        System.out.println(report);
    }

    /** Sends a request and returns the complete raw response as ISO-8859-1 text. */
    private static String probe(int port, String request) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.setTcpNoDelay(true);
            OutputStream os = socket.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                if (read < 0) {
                    break;
                }
                sink.write(buffer, 0, read);
                if (sink.size() > 512 * 1024) {
                    break;
                }
            }
            return sink.toString(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return "[probe failed: " + e + "]";
        }
    }
}
