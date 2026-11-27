package gg.moonrise.hawkeye.support.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.InstrumentRegistryEntry;
import io.papermc.paper.registry.data.TrimMaterialRegistryEntry;
import io.papermc.paper.registry.data.TrimPatternRegistryEntry;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.ConfirmationType;
import io.papermc.paper.registry.data.dialog.type.DialogListType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import io.papermc.paper.registry.data.dialog.type.ServerLinksType;
import io.papermc.paper.registry.set.RegistrySet;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.MusicInstrument;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Paper's dialog providers for tests, registered through {@code META-INF/services}: MockBukkit registers
 * none, so without this {@code Dialog.create} and the dialog builders throw. It implements what
 * {@code PaperDialogScreens} calls; everything else throws {@link UnsupportedOperationException}.
 */
public final class FakeDialogProvider implements DialogInstancesProvider, InlinedRegistryBuilderProvider {

    /** The id of every callback action. */
    public static final Key CALLBACK = Key.key("test", "dialog_callback");

    /** Created by the service loader. */
    public FakeDialogProvider() {
    }

    @Override
    public @NotNull Dialog createDialog(
            @NotNull Consumer<RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>> value) {
        FakeDialogParts.DialogFactory factory = new FakeDialogParts.DialogFactory();
        value.accept(factory);
        return factory.dialog();
    }

    @Override
    public @NotNull DialogBase.Builder dialogBaseBuilder(@NotNull Component title) {
        return new FakeDialogParts.BaseBuilder(title);
    }

    @Override
    public @NotNull ActionButton.Builder actionButtonBuilder(@NotNull Component label) {
        return new FakeDialogParts.ButtonBuilder(label);
    }

    @Override
    public @NotNull DialogAction.CustomClickAction register(@NotNull DialogActionCallback callback,
            @NotNull ClickCallback.Options options) {
        return new FakeDialogParts.FakeCallbackAction(CALLBACK, null, callback, options);
    }

    @Override
    public @NotNull PlainMessageDialogBody plainMessageDialogBody(@NotNull Component contents) {
        return plainMessageDialogBody(contents, 200);
    }

    @Override
    public @NotNull PlainMessageDialogBody plainMessageDialogBody(@NotNull Component contents, int width) {
        return new FakeDialogParts.FakePlainMessage(contents, width);
    }

    @Override
    public @NotNull TextDialogInput.Builder textBuilder(@NotNull String key, @NotNull Component label) {
        return new FakeDialogParts.TextBuilder(key, label);
    }

    @Override
    public @NotNull BooleanDialogInput.Builder booleanBuilder(@NotNull String key, @NotNull Component label) {
        return new FakeDialogParts.BooleanBuilder(key, label);
    }

    @Override
    public @NotNull MultiActionType.Builder multiAction(@NotNull List<ActionButton> actions) {
        return new FakeDialogParts.MultiActionBuilder(actions);
    }

    @Override
    public @NotNull ConfirmationType confirmation(@NotNull ActionButton yesButton, @NotNull ActionButton noButton) {
        return new FakeDialogParts.FakeConfirmation(yesButton, noButton);
    }

    @Override
    public @NotNull DialogAction.StaticAction staticAction(@NotNull ClickEvent<?> value) {
        throw unsupported("static actions");
    }

    @Override
    public @NotNull DialogAction.CommandTemplateAction commandTemplate(@NotNull String template) {
        throw unsupported("command templates");
    }

    @Override
    public @NotNull DialogAction.CustomClickAction customClick(@NotNull Key id, @Nullable BinaryTagHolder additions) {
        throw unsupported("custom click ids");
    }

    @Override
    public ItemDialogBody.@NotNull Builder itemDialogBodyBuilder(@NotNull ItemStack item) {
        throw unsupported("item bodies");
    }

    @Override
    public NumberRangeDialogInput.@NotNull Builder numberRangeBuilder(@NotNull String key, @NotNull Component label,
            float start, float end) {
        throw unsupported("number ranges");
    }

    @Override
    public SingleOptionDialogInput.@NotNull Builder singleOptionBuilder(@NotNull String key, @NotNull Component label,
            @NotNull List<SingleOptionDialogInput.OptionEntry> entries) {
        throw unsupported("single options");
    }

    @Override
    public SingleOptionDialogInput.@NotNull OptionEntry singleOptionEntry(@NotNull String id,
            @NotNull Component display, boolean initial) {
        throw unsupported("single options");
    }

    @Override
    public TextDialogInput.@NotNull MultilineOptions multilineOptions(@Nullable Integer maxLines,
            @Nullable Integer height) {
        throw unsupported("multi-line text");
    }

    @Override
    public DialogListType.@NotNull Builder dialogList(@NotNull RegistrySet<Dialog> dialogs) {
        throw unsupported("dialog lists");
    }

    @Override
    public @NotNull NoticeType notice() {
        throw unsupported("notices");
    }

    @Override
    public @NotNull NoticeType notice(@NotNull ActionButton action) {
        throw unsupported("notices");
    }

    @Override
    public @NotNull ServerLinksType serverLinks(@NotNull ActionButton exitAction, int columns, int buttonWidth) {
        throw unsupported("server links");
    }

    @Override
    public @NotNull MusicInstrument createInstrument(
            @NotNull Consumer<RegistryBuilderFactory<MusicInstrument, ? extends InstrumentRegistryEntry.Builder>> value) {
        throw unsupported("instruments");
    }

    @Override
    public @NotNull TrimMaterial createTrimMaterial(
            @NotNull Consumer<RegistryBuilderFactory<TrimMaterial, ? extends TrimMaterialRegistryEntry.Builder>> value) {
        throw unsupported("trim materials");
    }

    @Override
    public @NotNull TrimPattern createTrimPattern(
            @NotNull Consumer<RegistryBuilderFactory<TrimPattern, ? extends TrimPatternRegistryEntry.Builder>> value) {
        throw unsupported("trim patterns");
    }

    private static @NotNull UnsupportedOperationException unsupported(@NotNull String what) {
        return new UnsupportedOperationException("the fake dialog provider does not build " + what);
    }
}
