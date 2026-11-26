package gg.moonrise.hawkeye.core.service.diagnostic;

import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.diagnostic.Diagnostics;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.entity.diagnostic.PendingReport;
import gg.moonrise.hawkeye.entity.diagnostic.WebhookSettings;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The error and crash files for staff: which wait for review, marking them reviewed, whether to tell staff on
 * join, and the webhook settings the error log sends with (read from the reloadable configuration at every
 * report, so {@code /hawkeye reload} applies them at once). The files are read on the query pool and moved on
 * the error log's thread (on the query pool when the error log is not running), never on a server thread.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ErrorReportService {

    private final @NotNull Diagnostics diagnostics;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Environment environment;
    private final @NotNull HawkeyeExecutors executors;

    /** Hands the error log the configuration's webhook settings. */
    @PostConstruct
    public void start() {
        diagnostics.useWebhookSettings(this::webhookSettings);
    }

    /** Keeps the last settings for what is logged after the context closed (shutdown errors). */
    @PreDestroy
    public void stop() {
        WebhookSettings last = webhookSettings();
        diagnostics.useWebhookSettings(() -> last);
    }

    /**
     * The webhook settings now.
     *
     * @return the settings; {@link WebhookSettings#OFF} when no URL is set
     */
    public @NotNull WebhookSettings webhookSettings() {
        HawkeyeConfig config = configProvider.get();
        return config.errorReports().webhook(environment, config.server().name());
    }

    /**
     * Whether staff are told on join about files waiting for review.
     *
     * @return {@code error-reports.join-notice}
     */
    public boolean joinNotice() {
        return configProvider.get().errorReports().joinNotice();
    }

    /**
     * The files waiting for review.
     *
     * @return crashes first, then errors; fails when the folders cannot be read or the query pool is full
     */
    public @NotNull CompletableFuture<List<PendingReport>> pending() {
        return run(() -> {
            try {
                return diagnostics.pendingReports();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * How many files wait for review, from the folder listings alone (the join notice needs no more).
     *
     * @return the waiting crash and error files; fails when a folder cannot be listed or the query pool is full
     */
    public @NotNull CompletableFuture<PendingCount> pendingCount() {
        return run(() -> {
            try {
                return diagnostics.pendingCount();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Moves every waiting file to the {@code reviewed} folder of {@code errors/} or {@code crash/}. It is handed
     * over from the query pool: when the error log is not running, the error log moves the files on the thread
     * that asks.
     *
     * @return how many files were moved; fails when a file cannot be moved or the query pool is full
     */
    public @NotNull CompletableFuture<Integer> markReviewed() {
        return run(diagnostics::markReviewed).thenCompose(Function.identity());
    }

    private <T> @NotNull CompletableFuture<T> run(@NotNull Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
