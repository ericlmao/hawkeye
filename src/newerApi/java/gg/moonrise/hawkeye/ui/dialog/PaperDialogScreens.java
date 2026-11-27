package gg.moonrise.hawkeye.ui.dialog;

import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@link DialogScreens} on Paper 1.21.7 and later, the only class that touches Paper's dialog API. It uses
 * no Adventure API beyond components, {@code showDialog} and {@link ClickCallback.Options}, because Paper
 * 1.21.x ships Adventure 4 and 26.x Adventure 5 (no {@code ClickEvent} here, so no static actions).
 */
@Slf4j
@SpringComponent
@ConditionalOnApi(NewerApi.DIALOG)
public final class PaperDialogScreens implements DialogScreens {

    /** The width of every button. */
    private static final int BUTTON_WIDTH = 100;

    /** The most columns a listed dialog spreads its buttons over. */
    private static final int MAX_COLUMNS = 3;

    /**
     * A button's callback works once and for an hour: every press shows a fresh dialog with fresh
     * callbacks, so one use is enough, and Paper does not keep a callback alive for the default 12 hours.
     */
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofHours(1))
            .build();

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public boolean show(@NotNull Player player, @NotNull DialogScreen screen) {
        try {
            DialogBase base = base(screen);
            DialogType type = type(screen);
            Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
            player.showDialog(dialog);
            return true;
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not show a dialog to {}", player.getName(), e);
            return false;
        }
    }

    private static @NotNull DialogBase base(@NotNull DialogScreen screen) {
        List<DialogBody> body = screen.body().stream()
                .<DialogBody>map(line -> DialogBody.plainMessage(line, screen.width()))
                .toList();
        List<DialogInput> inputs = screen.fields().stream()
                .map(field -> input(field, screen.width()))
                .toList();
        return DialogBase.builder(screen.title())
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build();
    }

    private static @NotNull DialogInput input(@NotNull DialogScreen.Field field, int width) {
        return switch (field) {
            case DialogScreen.Field.Text text -> DialogInput.text(text.key(), text.label())
                    .width(width)
                    .initial(text.initial())
                    .maxLength(text.maxLength())
                    .build();
            case DialogScreen.Field.Toggle toggle -> DialogInput.bool(toggle.key(), toggle.label())
                    .initial(toggle.initial())
                    .build();
        };
    }

    private static @NotNull DialogType type(@NotNull DialogScreen screen) {
        return switch (screen.buttons()) {
            case DialogScreen.Buttons.Listed listed -> {
                List<ActionButton> actions = listed.actions().stream()
                        .map(press -> button(press, screen.fields()))
                        .toList();
                yield DialogType.multiAction(actions)
                        .exitAction(button(listed.exit()))
                        .columns(Math.min(MAX_COLUMNS, actions.size()))
                        .build();
            }
            case DialogScreen.Buttons.Choice choice -> DialogType.confirmation(
                    button(choice.confirm(), screen.fields()), button(choice.cancel()));
        };
    }

    private static @NotNull ActionButton button(@NotNull DialogScreen.Press press,
            @NotNull List<DialogScreen.Field> fields) {
        return ActionButton.builder(press.label())
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((response, audience) -> answer(press, fields, response), ONCE))
                .build();
    }

    /** A button without an action: the client just closes the dialog. */
    private static @NotNull ActionButton button(@NotNull DialogScreen.Close close) {
        return ActionButton.builder(close.label())
                .width(BUTTON_WIDTH)
                .build();
    }

    /**
     * Hands a press to its handler. It runs on the thread that handles the player's packet and touches
     * nothing but the response; the handler hops to the player's thread itself.
     */
    private static void answer(@NotNull DialogScreen.Press press, @NotNull List<DialogScreen.Field> fields,
            @NotNull DialogResponseView response) {
        try {
            press.handler().accept(answers(fields, response));
        } catch (RuntimeException e) {
            log.warn("A dialog button's handler failed", e);
        }
    }

    private static @NotNull DialogAnswers answers(@NotNull List<DialogScreen.Field> fields,
            @NotNull DialogResponseView response) {
        Map<String, String> texts = new HashMap<>();
        Map<String, Boolean> toggles = new HashMap<>();
        for (DialogScreen.Field field : fields) {
            switch (field) {
                case DialogScreen.Field.Text text -> {
                    String typed = response.getText(text.key());
                    texts.put(text.key(), typed == null ? "" : typed);
                }
                case DialogScreen.Field.Toggle toggle -> {
                    Boolean ticked = response.getBoolean(toggle.key());
                    toggles.put(toggle.key(), ticked != null && ticked);
                }
            }
        }
        return new DialogAnswers(texts, toggles);
    }
}
