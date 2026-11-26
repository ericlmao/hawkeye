package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.core.service.diagnostic.ErrorReportService;
import gg.moonrise.hawkeye.ui.diagnostic.ReportReplies;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import lombok.RequiredArgsConstructor;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /hawkeye errors}: the error and crash files waiting for review, and {@code /hawkeye errors reviewed} to
 * move them to the {@code reviewed} folders so the notice on join stops counting them.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ErrorsCommand implements PaperCommand {

    private final @NotNull ErrorReportService reports;
    private final @NotNull ReportReplies replies;

    @Command(Permissions.ROOT + " errors")
    @CommandDescription("Lists the error and crash reports waiting for review")
    @Permission(Permissions.ERRORS)
    public void list(@NotNull CommandSourceStack source) {
        replies.list(source.getSender(), reports.pending());
    }

    @Command(Permissions.ROOT + " errors reviewed")
    @CommandDescription("Marks every waiting error and crash report as reviewed")
    @Permission(Permissions.ERRORS)
    public void reviewed(@NotNull CommandSourceStack source) {
        replies.reviewed(source.getSender(), reports.markReviewed());
    }
}
