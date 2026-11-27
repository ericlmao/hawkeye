package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.message.DevMessages;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.dev.DevDataListener;
import gg.moonrise.hawkeye.core.service.dev.DevDataOutcome;
import gg.moonrise.hawkeye.core.service.dev.DevDataProgress;
import gg.moonrise.hawkeye.core.service.dev.DevDataRequest;
import gg.moonrise.hawkeye.core.service.dev.DevDataService;
import gg.moonrise.hawkeye.core.service.dev.DevWorld;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.util.text.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /hawkeye dev generate <count> [days]}, {@code dev status} and {@code dev cancel}: dev mode's fake data
 * ({@code config.yml: dev-mode.enabled}), for developing Hawkeye and never for a real server. Every command
 * answers only the disabled text while dev mode is off. Generating runs off the server thread; replies arrive on
 * the sender's own thread.
 *
 * <p>The optional day count is a nullable argument: an optional {@code [arg]} without {@code @Default} arrives
 * as {@code null} in Cloud 2.0.0.
 */
@SpringComponent
@RequiredArgsConstructor
public final class DevCommand implements PaperCommand {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull DevDataService devDataService;
    private final @NotNull ServerThreads threads;

    @Command(Permissions.ROOT + " dev generate <count> [days]")
    @CommandDescription("Dev mode: writes generated fake history into the database")
    @Permission(Permissions.DEV)
    public void generate(@NotNull CommandSourceStack source, @Argument("count") int count,
            @Argument("days") @Nullable Integer days) {
        CommandSender sender = source.getSender();
        if (!devDataService.enabled()) {
            reply(sender, messages().getDisabled());
            return;
        }
        if (count < 1) {
            reply(sender, messages().getInvalidCount());
            return;
        }
        if (days != null && days < 1) {
            reply(sender, messages().getInvalidDays());
            return;
        }
        DevDataRequest request = new DevDataRequest(count, days == null ? DevDataRequest.DEFAULT_DAYS : days,
                worlds(sender), sender.getName());
        devDataService.start(request, new SenderListener(sender)).whenComplete((outcome, error) -> {
            DevDataOutcome reply = outcome != null ? outcome : new DevDataOutcome.Failed(
                    Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName()));
            threads.runFor(sender, () -> tell(sender, reply));
        });
    }

    @Command(Permissions.ROOT + " dev status")
    @CommandDescription("Dev mode: shows how generating fake data goes")
    @Permission(Permissions.DEV)
    public void status(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!devDataService.enabled()) {
            reply(sender, messages().getDisabled());
            return;
        }
        Optional<DevDataProgress> progress = devDataService.progress();
        String template = progress.isPresent() ? messages().getStatus() : messages().getNotRunning();
        TagResolver.Single[] placeholders = progress.map(this::placeholders).orElse(new TagResolver.Single[0]);
        threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
    }

    @Command(Permissions.ROOT + " dev cancel")
    @CommandDescription("Dev mode: stops generating fake data after its current batch")
    @Permission(Permissions.DEV)
    public void cancel(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!devDataService.enabled()) {
            reply(sender, messages().getDisabled());
            return;
        }
        reply(sender, devDataService.cancel() ? messages().getCancelling() : messages().getNotRunning());
    }

    private void tell(@NotNull CommandSender sender, @NotNull DevDataOutcome outcome) {
        DevMessages messages = messages();
        switch (outcome) {
            case DevDataOutcome.Finished finished -> Message.of(messages.getFinished()).send(sender,
                    placeholders(finished.totals()));
            case DevDataOutcome.Cancelled cancelled -> Message.of(messages.getCancelled()).send(sender,
                    placeholders(cancelled.totals()));
            case DevDataOutcome.Disabled disabled -> Message.of(messages.getDisabled()).send(sender);
            case DevDataOutcome.AlreadyRunning running -> Message.of(messages.getAlreadyRunning()).send(sender);
            case DevDataOutcome.NotReady notReady -> Message.of(general().getStorageNotReady()).send(sender);
            case DevDataOutcome.NoNodeLeft noNodeLeft -> Message.of(messages.getNoNodeLeft()).send(sender);
            case DevDataOutcome.Failed failed -> Message.of(general().getOperationFailed()).send(sender,
                    Placeholder.unparsed("reason", failed.reason()));
        }
    }

    /**
     * The worlds fake data happens in, read on the calling thread: every world of the server around its spawn,
     * and for a player their own world first, around where they stand.
     */
    private static @NotNull List<DevWorld> worlds(@NotNull CommandSender sender) {
        List<DevWorld> worlds = new ArrayList<>();
        World own = null;
        if (sender instanceof Player player) {
            Location at = player.getLocation();
            own = at.getWorld();
            if (own != null) {
                worlds.add(world(own, at));
            }
        }
        for (World world : sender.getServer().getWorlds()) {
            if (!world.equals(own) && worlds.size() < DevDataRequest.MAX_WORLDS) {
                worlds.add(world(world, world.getSpawnLocation()));
            }
        }
        return worlds;
    }

    private static @NotNull DevWorld world(@NotNull World world, @NotNull Location centre) {
        DevWorld.Dimension dimension = switch (world.getEnvironment()) {
            case NETHER -> DevWorld.Dimension.NETHER;
            case THE_END -> DevWorld.Dimension.END;
            default -> DevWorld.Dimension.OVERWORLD;
        };
        return new DevWorld(world.getName(), dimension, centre.getBlockX(), centre.getBlockY(), centre.getBlockZ(),
                world.getMinHeight(), world.getMaxHeight() - 1);
    }

    private @NotNull TagResolver.Single @NotNull [] placeholders(@NotNull DevDataProgress progress) {
        String remaining = progress.remainingMillis().isPresent()
                ? Durations.format(Duration.ofMillis(progress.remainingMillis().getAsLong()))
                : messages().getRemainingUnknown();
        return new TagResolver.Single[] {
            Placeholder.unparsed("node", progress.node()),
            count("count", progress.count()),
            count("events", progress.written()),
            count("players", progress.players()),
            count("days", progress.days()),
            Placeholder.unparsed("percent", Integer.toString(progress.percent())),
            Placeholder.unparsed("rate", String.format(Locale.ROOT, "%.0f", progress.eventsPerSecond())),
            Placeholder.unparsed("duration", Durations.format(Duration.ofMillis(progress.elapsedMillis()))),
            Placeholder.unparsed("remaining", remaining)
        };
    }

    private static @NotNull TagResolver.Single count(@NotNull String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    private void reply(@NotNull CommandSender sender, @NotNull String template) {
        threads.runFor(sender, () -> Message.of(template).send(sender));
    }

    private @NotNull DevMessages messages() {
        return configProvider.messages().getDev();
    }

    private @NotNull GeneralMessages general() {
        return configProvider.messages().getGeneral();
    }

    /** Tells the sender how their run goes, on their own thread. */
    private final class SenderListener implements DevDataListener {

        private final @NotNull CommandSender sender;

        private SenderListener(@NotNull CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public void started(@NotNull DevDataProgress progress) {
            String template = messages().getStarted();
            TagResolver.Single[] placeholders = placeholders(progress);
            threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
        }

        @Override
        public void progress(@NotNull DevDataProgress progress) {
            String template = messages().getProgress();
            TagResolver.Single[] placeholders = placeholders(progress);
            threads.runFor(sender, () -> Message.of(template).send(sender, placeholders));
        }
    }
}
