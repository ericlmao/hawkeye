package gg.moonrise.hawkeye.ui.diagnostic;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.ErrorMessages;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.diagnostic.PendingCount;
import gg.moonrise.hawkeye.entity.diagnostic.PendingReport;
import gg.moonrise.hawkeye.entity.diagnostic.ProblemKind;
import gg.moonrise.hawkeye.util.text.ByteSizes;
import gg.moonrise.hawkeye.util.text.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Tells staff about the error and crash files waiting for review, on the receiver's own thread, from the active
 * {@code messages.yml} ({@code errors} section).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class ReportReplies {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ServerThreads threads;
    private final @NotNull Clock clock;

    /**
     * Sends the list of files waiting for review once it is read.
     *
     * @param sender  who asked
     * @param pending the files being read
     */
    public void list(@NotNull CommandSender sender, @NotNull CompletableFuture<List<PendingReport>> pending) {
        pending.whenComplete((reports, error) -> threads.runFor(sender, () -> {
            ErrorMessages messages = messages();
            if (error != null) {
                unavailable(sender, error);
                return;
            }
            if (reports.isEmpty()) {
                Message.of(messages.getListEmpty()).send(sender);
                return;
            }
            Message.of(messages.getListHeader()).send(sender,
                    Placeholder.unparsed("count", Integer.toString(reports.size())));
            for (PendingReport report : reports) {
                Message.of(messages.getListEntry()).send(sender,
                        Placeholder.parsed("kind", kind(messages, report.kind())),
                        Placeholder.unparsed("folder", report.kind().folder()),
                        Placeholder.unparsed("file", report.fileName()),
                        Placeholder.unparsed("time", Durations.format(Duration.ofMillis(
                                Math.max(0, clock.millis() - report.modifiedMillis())))),
                        Placeholder.unparsed("size", ByteSizes.format(report.sizeBytes())),
                        Placeholder.unparsed("problems", Integer.toString(report.problems())),
                        Placeholder.unparsed("first", report.firstProblem()));
            }
            Message.of(messages.getListFooter()).send(sender);
        }));
    }

    /**
     * Confirms that files were marked reviewed once they are moved.
     *
     * @param sender who asked
     * @param moved  how many files are being moved
     */
    public void reviewed(@NotNull CommandSender sender, @NotNull CompletableFuture<Integer> moved) {
        moved.whenComplete((count, error) -> threads.runFor(sender, () -> {
            if (error != null) {
                unavailable(sender, error);
                return;
            }
            Message.of(messages().getReviewed()).send(sender, Placeholder.unparsed("count", Integer.toString(count)));
        }));
    }

    /**
     * Tells a joining staff member how many files wait, if any do. When the folders cannot be listed the player is
     * told nothing and the failure is logged.
     *
     * @param receiver the player who joined
     * @param pending  the waiting files being counted
     */
    public void joinNotice(@NotNull CommandSender receiver, @NotNull CompletableFuture<PendingCount> pending) {
        pending.thenAccept(count -> {
            if (count.isEmpty()) {
                return;
            }
            threads.runFor(receiver, () -> {
                ErrorMessages messages = messages();
                StringBuilder parts = new StringBuilder();
                if (count.crashes() > 0) {
                    parts.append(messages.getJoinNoticeCrashes().replace("<count>",
                            Integer.toString(count.crashes())));
                }
                if (count.errors() > 0) {
                    if (!parts.isEmpty()) {
                        parts.append(messages.getJoinNoticeAnd());
                    }
                    parts.append(messages.getJoinNoticeErrors().replace("<count>", Integer.toString(count.errors())));
                }
                Message.of(messages.getJoinNotice()).send(receiver, Placeholder.parsed("reports", parts.toString()));
            });
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("Could not tell {} on join about the error and crash files waiting for review",
                        receiver.getName(), cause(error));
            }
        });
    }

    private void unavailable(@NotNull CommandSender sender, @NotNull Throwable error) {
        Throwable cause = cause(error);
        String reason = Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
        Message.of(messages().getUnavailable()).send(sender, Placeholder.unparsed("reason", reason));
    }

    private static @NotNull Throwable cause(@NotNull Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static @NotNull String kind(@NotNull ErrorMessages messages, @NotNull ProblemKind kind) {
        return switch (kind) {
            case CRASH -> messages.getKindCrash();
            case ERROR -> messages.getKindError();
        };
    }

    private @NotNull ErrorMessages messages() {
        return configProvider.messages().getErrors();
    }
}
