package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.message.MessagesConfig;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Sends lookup results to the sender that asked, on the sender's own thread, rendered from the active
 * {@code messages.yml} ({@link LookupLines}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class LookupReplies {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ServerThreads threads;
    private final @NotNull Clock clock;

    /**
     * Sends a page (or why there is none) once the lookup completes.
     *
     * @param sender who asked
     * @param result the pending page
     */
    public void page(@NotNull CommandSender sender, @NotNull CompletableFuture<PageResult> result) {
        result.whenComplete((page, error) -> {
            PageResult shown = error == null ? page : new PageResult.Unavailable(failed(error));
            threads.runFor(sender, () -> send(sender, lines().page(shown)));
        });
    }

    /**
     * Sends a count (or why there is none) once the lookup completes.
     *
     * @param sender who asked
     * @param result the pending count
     */
    public void count(@NotNull CommandSender sender, @NotNull CompletableFuture<LookupOutcome> result) {
        result.whenComplete((count, error) -> {
            LookupOutcome shown = error == null ? count : failed(error);
            threads.runFor(sender, () -> send(sender, lines().count(shown)));
        });
    }

    /**
     * The renderer for the active messages and the current time.
     *
     * @return the renderer
     */
    public @NotNull LookupLines lines() {
        MessagesConfig messages = configProvider.messages();
        EventLines events = new EventLines(messages.getLookup(), messages.getActions(),
                configProvider.get().server().name(), clock.instant(), ZoneId.systemDefault(),
                EventLines.CALLBACK_BOOKS);
        return new LookupLines(messages, events);
    }

    private static void send(@NotNull CommandSender sender, @NotNull List<Component> lines) {
        lines.forEach(sender::sendMessage);
    }

    /**
     * Logs a lookup that failed with an exception and turns it into the outcome shown for it.
     *
     * @param error what the lookup's future completed with; a {@link CompletionException} is unwrapped
     * @return the failure, its reason the cause's message (else its class name)
     */
    public static @NotNull LookupOutcome.Failed failed(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        log.error("A lookup failed", cause);
        return new LookupOutcome.Failed(Objects.requireNonNullElse(cause.getMessage(),
                cause.getClass().getSimpleName()));
    }
}
