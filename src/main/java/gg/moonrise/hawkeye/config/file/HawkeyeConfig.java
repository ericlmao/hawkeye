package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import gg.moonrise.hawkeye.config.env.Environment;
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code config.yml} model. ConfigLib derives every key from the component name in lower kebab
 * case ({@code flushIntervalMillis} is {@code flush-interval-millis}), so renaming or reordering a
 * component changes the operator's file: treat it as a data migration. Player-facing text lives in
 * {@code messages.yml} ({@code config.message.MessagesConfig}).
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class HawkeyeConfig {

    @Comment("This server's identity. Every server that shares a database needs its own name.")
    private ServerSettings server = new ServerSettings();

    @Comment({"The home database: this server writes here. SQLITE needs no setup; MYSQL, MARIADB and",
            "POSTGRESQL let several servers share one database."})
    private StorageSettings storage = new StorageSettings();

    @Comment("The write queue between the game threads and the database.")
    private WriterSettings writer = new WriterSettings();

    @Comment("What is recorded. Every switch takes effect on /hawkeye reload.")
    private LoggingSettings logging = new LoggingSettings();

    @Comment("Lookups (/hawkeye lookup) and the inspector (/hawkeye inspect).")
    private LookupSettings lookup = new LookupSettings();

    @Comment("Rollback, restore and preview.")
    private RollbackSettings rollback = new RollbackSettings();

    @Comment("Deleting old data, by hand (/hawkeye purge) and automatically.")
    private PurgeSettings purge = new PurgeSettings();

    @Comment("Multi-server: other shards to read and the bus for network jobs.")
    private NetworkSettings network = new NetworkSettings();

    @Comment("The packed archive for old events.")
    private ArchiveSettings archive = new ArchiveSettings();

    @Comment({"Error and crash files (plugins/Hawkeye/errors and crash): a Discord webhook and the notice staff get",
            "on join. Every key takes effect on /hawkeye reload."})
    private ErrorReportSettings errorReports = new ErrorReportSettings();

    @Comment({"Features still being tried out: off by default, and they may change or go away in any release.",
            "Every switch takes effect on /hawkeye reload."})
    private ExperimentalSettings experimental = new ExperimentalSettings();

    /**
     * Problems that make this configuration unusable, an unset environment variable among them.
     *
     * @param environment where {@code ${NAME}} references are looked up
     * @return one message per problem; empty when the configuration is valid
     */
    public @NotNull List<String> problems(@NotNull Environment environment) {
        List<String> problems = new ArrayList<>();
        problems.addAll(server.problems());
        problems.addAll(storage.problems("storage", environment));
        problems.addAll(writer.problems());
        problems.addAll(lookup.problems());
        problems.addAll(rollback.problems());
        problems.addAll(purge.problems());
        problems.addAll(network.problems(environment));
        problems.addAll(archive.problems());
        problems.addAll(errorReports.problems(environment));
        return problems;
    }
}
