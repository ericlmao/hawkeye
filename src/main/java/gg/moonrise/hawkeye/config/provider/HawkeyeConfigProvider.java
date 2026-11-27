package gg.moonrise.hawkeye.config.provider;

import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurationProperties;
import gg.moonrise.engine.config.Configuration;
import gg.moonrise.engine.state.Reloadable;
import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.file.CommandsConfig;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.message.MessagesConfig;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Owns the {@code config.yml}, {@code messages.yml} and {@code commands.yml} snapshot. The files are
 * read through the plugin-engine {@link Configuration} (ConfigLib), which writes every default on first
 * start and adds keys missing from an older file. A reload publishes every file or none: a file that
 * fails to load or validate keeps the previous snapshot. Validation checks that every environment variable the
 * credentials reference is set; a failure's reason never contains a credential ({@link ConfigFailures}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeConfigProvider implements Reloadable {

    /** The settings file inside the plugin folder. */
    public static final String CONFIG_FILE = "config.yml";

    /** The messages file inside the plugin folder. */
    public static final String MESSAGES_FILE = "messages.yml";

    /** The command aliases file inside the plugin folder. */
    public static final String COMMANDS_FILE = "commands.yml";

    private static final String CONFIG_HEADER = """
            Hawkeye configuration. /hawkeye reload applies the logging, lookup, rollback, purge, archive,
            error-reports, experimental and dev-mode sections; server, storage, writer and network changes need
            a restart.
            Database and Redis credentials are best read from environment variables: ${NAME} in a value.
            """;

    private static final String MESSAGES_HEADER = """
            Hawkeye messages in MiniMessage format: https://docs.advntr.dev/minimessage/format
            /hawkeye reload applies changes.
            """;

    private static final String COMMANDS_HEADER = """
            Hawkeye command aliases. Commands are registered at startup: changes here need a restart.
            Permissions do not change: an alias needs the same permission as the command it names.
            """;

    private final @NotNull JavaPlugin plugin;
    private final @NotNull Environment environment;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /** Reads the files at startup; broken files leave the defaults active until a reload succeeds. */
    @PostConstruct
    public void load() {
        if (reloadConfig() instanceof ReloadResult.Failed failed) {
            log.error("Using the default configuration: {}", failed.reason());
            current.compareAndSet(null, new Snapshot(new HawkeyeConfig(), new MessagesConfig(), new CommandsConfig()));
        }
    }

    /**
     * The active settings.
     *
     * @return the settings last read successfully, or the defaults
     */
    public @NotNull HawkeyeConfig get() {
        return snapshot().config();
    }

    /**
     * The active messages.
     *
     * @return the messages last read successfully, or the defaults
     */
    public @NotNull MessagesConfig messages() {
        return snapshot().messages();
    }

    /**
     * The active command aliases. The command registry reads them once at startup.
     *
     * @return the aliases last read successfully, or the defaults
     */
    public @NotNull CommandsConfig commands() {
        return snapshot().commands();
    }

    /** Run by {@code PaperPlugin.reload()} for every {@link Reloadable} bean. */
    @Override
    public void reload() {
        if (reloadConfig() instanceof ReloadResult.Failed failed) {
            log.error("Keeping the previous configuration: {}", failed.reason());
        }
    }

    /**
     * Reads every file and, when all are valid, makes them the active snapshot.
     *
     * @return whether the files were read, and which restart-only settings changed
     */
    public @NotNull ReloadResult reloadConfig() {
        Path folder = plugin.getDataPath();
        try {
            HawkeyeConfig config = readConfig(folder);
            MessagesConfig messages = Configuration.config(folder.resolve(MESSAGES_FILE).toFile(),
                    MessagesConfig.class, builder -> properties(builder, MESSAGES_HEADER)).get();
            CommandsConfig commands = Configuration.config(folder.resolve(COMMANDS_FILE).toFile(),
                    CommandsConfig.class, builder -> properties(builder, COMMANDS_HEADER)).get();
            List<String> problems = config.problems(environment);
            if (!problems.isEmpty()) {
                return new ReloadResult.Failed(CONFIG_FILE + ": " + String.join("; ", problems));
            }
            List<String> commandProblems = commands.problems();
            if (!commandProblems.isEmpty()) {
                return new ReloadResult.Failed(COMMANDS_FILE + ": " + String.join("; ", commandProblems));
            }
            Snapshot next = new Snapshot(config, messages, commands);
            Snapshot previous = current.getAndSet(next);
            if (config.devMode().enabled() && (previous == null || !previous.config().devMode().enabled())) {
                // INFO, never WARN: the error log files every WARN as a problem waiting for review.
                log.info("Dev mode is on (config.yml: dev-mode.enabled): /hawkeye dev can write fake history into "
                        + "this database. It is for developing Hawkeye, never for a real server.");
            }
            return new ReloadResult.Reloaded(previous == null ? List.of() : restartOnly(previous, next));
        } catch (IOException | RuntimeException e) {
            return new ReloadResult.Failed(ConfigFailures.reason(e));
        }
    }

    private @NotNull Snapshot snapshot() {
        return Objects.requireNonNull(current.get(), "the configuration is read in @PostConstruct");
    }

    /**
     * Reads {@code config.yml} as {@link #reloadConfig()} does (writing defaults and missing keys back), without
     * validating it. The error log reads its webhook this way before the Spring context exists, so a context that
     * fails to start still reaches the webhook.
     *
     * @param folder the plugin folder
     * @return the settings in the file
     * @throws IOException      when the folder cannot be created
     * @throws RuntimeException when the file is not valid YAML or holds a wrong type (ConfigLib's exceptions;
     *                          their messages may quote the file, so give them to {@link ConfigFailures})
     */
    public static @NotNull HawkeyeConfig readConfig(@NotNull Path folder) throws IOException {
        Files.createDirectories(folder);
        return Configuration.config(folder.resolve(CONFIG_FILE).toFile(), HawkeyeConfig.class,
                builder -> properties(builder, CONFIG_HEADER)).get();
    }

    private static @NotNull List<String> restartOnly(@NotNull Snapshot previous, @NotNull Snapshot next) {
        HawkeyeConfig before = previous.config();
        HawkeyeConfig after = next.config();
        List<String> changed = new ArrayList<>();
        if (!before.server().equals(after.server())) {
            changed.add("server");
        }
        if (!before.storage().equals(after.storage())) {
            changed.add("storage");
        }
        if (!before.writer().equals(after.writer())) {
            changed.add("writer");
        }
        if (!before.network().equals(after.network())) {
            changed.add("network");
        }
        if (!previous.commands().equals(next.commands())) {
            changed.add(COMMANDS_FILE);
        }
        return changed;
    }

    private static @NotNull YamlConfigurationProperties.Builder<?> properties(
            @NotNull YamlConfigurationProperties.Builder<?> builder, @NotNull String header) {
        builder.setNameFormatter(NameFormatters.LOWER_KEBAB_CASE);
        builder.inputNulls(true);
        builder.outputNulls(false);
        builder.header(header);
        return builder;
    }

    private record Snapshot(@NotNull HawkeyeConfig config, @NotNull MessagesConfig messages,
                            @NotNull CommandsConfig commands) {
    }
}
