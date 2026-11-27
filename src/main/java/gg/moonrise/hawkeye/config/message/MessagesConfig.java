package gg.moonrise.hawkeye.config.message;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import lombok.Getter;

/**
 * The {@code messages.yml} model: every player-facing text, in MiniMessage
 * (https://docs.advntr.dev/minimessage/format). One section per feature; keys are lower kebab case
 * of the field names and are a contract with operators.
 */
@Getter
@Configuration
public final class MessagesConfig {

    @Comment("Shared replies.")
    private GeneralMessages general = new GeneralMessages();

    @Comment("Why a parameter list (u: t: r: a: i: e: s:) was refused.")
    private QueryMessages query = new QueryMessages();

    @Comment("/hawkeye lookup, near and page.")
    private LookupMessages lookup = new LookupMessages();

    @Comment("How each action reads in lookup results and the inspector.")
    private ActionMessages actions = new ActionMessages();

    @Comment("/hawkeye inspect and inspector clicks.")
    private InspectMessages inspect = new InspectMessages();

    @Comment("/hawkeye rollback, restore, apply, cancel and undo.")
    private RollbackMessages rollback = new RollbackMessages();

    @Comment("/hawkeye purge and the automatic purge.")
    private PurgeMessages purge = new PurgeMessages();

    @Comment("/hawkeye import coreprotect, status and cancel.")
    private ImportMessages importing = new ImportMessages();

    @Comment("/hawkeye status and /hawkeye network.")
    private StatusMessages status = new StatusMessages();

    @Comment("/hawkeye errors and the notice on join about error and crash files.")
    private ErrorMessages errors = new ErrorMessages();

    @Comment("/hawkeye lookup and near as a dialog (config.yml: experimental.dialog-lookup).")
    private LookupDialogMessages lookupDialog = new LookupDialogMessages();

    @Comment("/hawkeye dev generate, status and cancel (config.yml: dev-mode.enabled).")
    private DevMessages dev = new DevMessages();
}
