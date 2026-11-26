package gg.moonrise.hawkeye.core.diagnostic.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import gg.moonrise.hawkeye.core.diagnostic.ConsoleLines;
import gg.moonrise.hawkeye.core.diagnostic.WrittenReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The webhook against a local HTTP server standing in for Discord. */
public class DiscordWebhookTest {

    private static final String TOKEN = "s3cr3t-t0k3n";
    private static final String LOGGER = DiscordWebhook.class.getName();

    @TempDir
    private Path folder;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    /** Holds every request until it is opened; open unless a test closes it. */
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private final CountDownLatch arrived = new CountDownLatch(1);
    private ConsoleLines console;
    private Path file;

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/webhooks/1/" + TOKEN, exchange -> {
            arrived.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            requests.incrementAndGet();
            received.add(new Received(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Integer status = statuses.poll();
            int code = status == null ? 204 : status;
            byte[] body = code == 429 ? "{\"retry_after\": 0.05, \"global\": false}".getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        file = folder.resolve("Crash-2026-11-25-14-03.txt");
        Files.writeString(file, "Hawkeye crash report, started ...\n#1 ERROR at ...\n");
        console = ConsoleLines.attach();
    }

    @AfterEach
    public void tearDown() {
        console.close();
        server.stop(0);
    }

    @Test
    public void aReportIsPostedAsMultipartWithItsFile() {
        DiscordWebhook webhook = webhook();

        webhook.submit(report(ProblemKind.CRASH), settings(true, true));
        webhook.close(Duration.ofSeconds(5));

        assertEquals(1, received.size());
        Received request = received.getFirst();
        assertEquals("POST", request.method());
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary=hawkeye-"), request.contentType());
        assertTrue(request.body().contains("name=\"payload_json\""), request.body());
        assertTrue(request.body().contains("\"title\":\"Crash on lobby\""), request.body());
        assertTrue(request.body().contains("\"value\":\"crash/Crash-2026-11-25-14-03.txt\""), request.body());
        assertTrue(request.body().contains("filename=\"Crash-2026-11-25-14-03.txt\""), request.body());
        assertTrue(request.body().contains("Hawkeye crash report, started ...\n#1 ERROR at ..."), request.body());
        assertTrue(console.of(LOGGER).isEmpty(), console.events().toString());
    }

    @Test
    public void aKindThatIsSwitchedOffIsNotSent() {
        DiscordWebhook webhook = webhook();

        webhook.submit(report(ProblemKind.CRASH), settings(true, false));
        webhook.submit(report(ProblemKind.ERROR), settings(false, true));
        webhook.submit(report(ProblemKind.ERROR), WebhookSettings.OFF);
        webhook.close(Duration.ofSeconds(5));

        assertEquals(0, requests.get());
    }

    @Test
    public void aRateLimitIsWaitedOutAndRetriedOnce() {
        statuses.add(429);
        DiscordWebhook webhook = webhook();

        webhook.submit(report(ProblemKind.ERROR), settings(true, true));
        webhook.close(Duration.ofSeconds(5));

        assertEquals(2, requests.get());
        assertTrue(console.of(LOGGER).isEmpty(), console.events().toString());
    }

    @Test
    public void aRefusedReportIsLoggedOnceWithoutTheUrl() {
        statuses.add(404);
        statuses.add(404);
        DiscordWebhook webhook = webhook();

        webhook.submit(report(ProblemKind.ERROR), settings(true, true));
        webhook.submit(report(ProblemKind.ERROR), settings(true, true));
        webhook.close(Duration.ofSeconds(5));

        assertEquals(2, requests.get());
        List<LogEvent> lines = console.of(LOGGER);
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.getFirst().getMessage().getFormattedMessage();
        assertTrue(line.contains("HTTP 404"), line);
        assertFalse(line.contains(TOKEN), line);
    }

    @Test
    public void reportsNotSentAreNamedUntilAPostGetsThrough() throws InterruptedException {
        gate = new CountDownLatch(1);
        statuses.add(204);
        statuses.add(500);
        DiscordWebhook webhook = webhook();

        // The first is posted and held at the gate; the queue takes the next QUEUE, and two more are not sent.
        webhook.submit(report(ProblemKind.ERROR), settings(true, true));
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < DiscordWebhook.QUEUE + 2; i++) {
            webhook.submit(report(ProblemKind.ERROR), settings(true, true));
        }
        gate.countDown();
        webhook.close(Duration.ofSeconds(10));

        assertEquals(1 + DiscordWebhook.QUEUE, received.size());
        String notSent = "\"value\":\"2 earlier report(s): too many at once\"";
        assertFalse(received.get(0).body().contains(notSent), received.get(0).body());
        assertTrue(received.get(1).body().contains(notSent), "named in the refused post: " + received.get(1).body());
        assertTrue(received.get(2).body().contains(notSent), "named again after it: " + received.get(2).body());
        assertFalse(received.get(3).body().contains(notSent), "only until one gets through: " + received.get(3).body());
    }

    @Test
    public void anUnreachableWebhookIsLoggedWithoutItsToken() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        DiscordWebhook webhook = webhook();

        webhook.submit(report(ProblemKind.CRASH), new WebhookSettings("http://127.0.0.1:" + closedPort
                + "/api/webhooks/1/" + TOKEN, true, true, "lobby"));
        webhook.close(Duration.ofSeconds(15));

        List<LogEvent> lines = console.of(LOGGER);
        assertEquals(1, lines.size(), lines.toString());
        assertFalse(lines.getFirst().getMessage().getFormattedMessage().contains(TOKEN),
                lines.getFirst().getMessage().getFormattedMessage());
    }

    private @NotNull DiscordWebhook webhook() {
        return new DiscordWebhook(report -> "crash/" + report.getFileName(), "Paper 26.3-262 (MC: 26.3)");
    }

    private @NotNull WebhookSettings settings(boolean errors, boolean crashes) {
        return new WebhookSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/" + TOKEN,
                errors, crashes, "lobby");
    }

    private @NotNull WrittenReport report(@NotNull ProblemKind kind) {
        return new WrittenReport(1, kind, "ERROR", "Hawkeye could not load: boom", file, 0);
    }

    private record Received(@NotNull String method, @NotNull String contentType, @NotNull String body) {
    }
}
