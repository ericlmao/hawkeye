package gg.moonrise.hawkeye.core.diagnostic.webhook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.moonrise.hawkeye.core.diagnostic.WrittenReport;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import gg.moonrise.hawkeye.util.log.LogMarkers;
import gg.moonrise.hawkeye.util.redact.Redactor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sends {@link WrittenReport}s to a Discord webhook from its own {@code Hawkeye-Webhook} thread, one at a time, so
 * a slow or unreachable webhook never holds up the error log or a server thread. At most {@link #QUEUE} reports
 * wait; more are counted and named in the next message that gets through. A {@code 429} is retried once after
 * Discord's {@code retry_after} (at most {@link #MAX_RETRY_WAIT}). A failure is logged once until a report gets through
 * again, without the URL (it holds the webhook's token), and never filed by the error log itself
 * ({@link LogMarkers#REPORTED}).
 */
@Slf4j
public final class DiscordWebhook implements AutoCloseable {

    /** Reports waiting to be sent at most. */
    public static final int QUEUE = 50;

    /** The longest wait a {@code 429} is honoured with before its one retry. */
    public static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Made by the webhook thread for the first report, so a server without a webhook never makes one. */
    private volatile @Nullable HttpClient client;
    private final @NotNull ExecutorService thread;
    private final @NotNull Function<Path, String> shownPath;
    private final @NotNull String server;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * A webhook sender; its thread starts with the first report.
     *
     * @param shownPath how a report file is named in the message (as the console names it)
     * @param server    the server software and version for the message
     */
    public DiscordWebhook(@NotNull Function<Path, String> shownPath, @NotNull String server) {
        this.shownPath = shownPath;
        this.server = server;
        this.thread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE),
                runnable -> {
                    Thread sender = new Thread(runnable, "Hawkeye-Webhook");
                    sender.setDaemon(true);
                    return sender;
                });
    }

    /**
     * Queues a report for the webhook; does nothing when the settings do not send its kind. The file is read now
     * (the error log's thread calls this right after writing it), so the post shows it as the problem's first time
     * left it, and a file marked reviewed meanwhile is still attached.
     *
     * @param report   the problem and its file
     * @param settings the webhook settings now
     */
    public void submit(@NotNull WrittenReport report, @NotNull WebhookSettings settings) {
        if (!settings.sends(report.kind())) {
            return;
        }
        byte[] file = read(report.file()).orElse(null);
        try {
            thread.execute(() -> send(report, settings, file));
        } catch (RejectedExecutionException e) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Sends what is queued, waiting at most {@code wait}, then stops the thread.
     *
     * @param wait how long to wait for queued reports
     */
    public void close(@NotNull Duration wait) {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                thread.shutdownNow();
            }
        } catch (InterruptedException e) {
            thread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        HttpClient made = client;
        if (made != null) {
            made.shutdownNow();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    /** The file to attach: empty when it is too large or cannot be read. */
    private static @NotNull Optional<byte[]> read(@NotNull Path file) {
        try {
            return Files.size(file) <= DiscordPayload.MAX_ATTACHMENT ? Optional.of(Files.readAllBytes(file))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void send(@NotNull WrittenReport report, @NotNull WebhookSettings settings, byte @Nullable [] file) {
        Redactor redactor = Redactor.of(secrets(settings.url()), List.of());
        // Named in this post; counted again when it does not get through, so the next one names them.
        long notSent = dropped.getAndSet(0);
        boolean sent = false;
        try {
            JsonObject json = DiscordPayload.json(report, settings.serverName(), server,
                    shownPath.apply(report.file()), file != null, notSent);
            String boundary = "hawkeye-" + UUID.randomUUID();
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("User-Agent", "Hawkeye")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(DiscordPayload.multipart(boundary, json,
                            report.file().getFileName().toString(), file)))
                    .build();
            HttpClient http = client();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                Thread.sleep(retryAfter(response).toMillis());
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() / 100 == 2) {
                sent = true;
                if (failing.compareAndSet(true, false)) {
                    log.info(LogMarkers.REPORTED, "The Discord webhook (error-reports.webhook-url) works again");
                }
            } else {
                failed("HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            failed(redactor.scrub(e.toString()));
        } finally {
            if (!sent) {
                dropped.addAndGet(notSent);
            }
        }
    }

    /** The webhook thread's client; only that thread calls this. */
    private @NotNull HttpClient client() {
        HttpClient made = client;
        if (made == null) {
            made = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            client = made;
        }
        return made;
    }

    private void failed(@NotNull String reason) {
        if (failing.compareAndSet(false, true)) {
            log.warn(LogMarkers.REPORTED, "Could not send a report to the Discord webhook (error-reports.webhook-url):"
                    + " {}; it is in its file all the same, and further failures are not logged until one gets "
                    + "through", reason);
        }
    }

    /** The URL and its path: an exception may quote either, and the path holds the token. */
    private static @NotNull List<String> secrets(@NotNull String url) {
        try {
            String path = URI.create(url).getRawPath();
            return path == null || path.length() < 2 ? List.of(url) : List.of(url, path);
        } catch (IllegalArgumentException e) {
            return List.of(url);
        }
    }

    private static @NotNull Duration retryAfter(@NotNull HttpResponse<String> response) {
        Optional<Duration> fromBody = Optional.empty();
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("retry_after")) {
                fromBody = Optional.of(Duration.ofMillis((long) (body.get("retry_after").getAsDouble() * 1000)));
            }
        } catch (RuntimeException e) {
            // Not JSON: the header is all there is.
        }
        Duration wait = fromBody.or(() -> response.headers().firstValue("Retry-After").map(seconds -> {
            try {
                return Duration.ofMillis((long) (Double.parseDouble(seconds) * 1000));
            } catch (NumberFormatException e) {
                return Duration.ofSeconds(1);
            }
        })).orElse(Duration.ofSeconds(1));
        return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
    }
}
