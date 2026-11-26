package gg.moonrise.hawkeye.core.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.file.StorageLogin;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.config.file.StorageType;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Locale;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Opens a HikariCP pool for a shard. SQLite runs in WAL mode (readers never block the writer) with
 * {@code synchronous=NORMAL} and a busy timeout; the server engines use the MariaDB and PostgreSQL
 * drivers resolved by the plugin loader, through a {@link RedactingDataSource}: the user, the password
 * and the parameters (with their {@code ${NAME}} references resolved) are driver properties of that data
 * source only, so neither Hikari's configuration nor any URL holds them, and the drivers' messages reach
 * the log without them.
 */
@SpringComponent
@RequiredArgsConstructor
public final class DatabaseFactory {

    /**
     * What every Hawkeye pool's name starts with ({@code Hawkeye-home}). HikariCP starts its log lines with the
     * pool name, which is how the error log tells Hawkeye's pools from other plugins'.
     */
    public static final String POOL_PREFIX = "Hawkeye-";

    private final @NotNull Environment environment;

    /**
     * Opens a pool. Nothing is read or written yet; the first connection is made on first use.
     *
     * @param shard      the shard name, used in the pool name
     * @param settings   the connection settings
     * @param dataFolder the plugin folder SQLite files are relative to
     * @return the database
     */
    public @NotNull Database open(@NotNull String shard, @NotNull StorageSettings settings, @NotNull Path dataFolder) {
        HikariDataSource dataSource = new HikariDataSource(config(shard, settings, dataFolder));
        return new Database(shard, Dialect.of(settings.type()), new Tables(settings.tablePrefix()), dataSource,
                dataSource);
    }

    /**
     * The pool configuration {@link #open} uses. For a server engine it holds a {@link RedactingDataSource}
     * and no user, password, URL or driver property.
     *
     * @param shard      the shard name, used in the pool name
     * @param settings   the connection settings
     * @param dataFolder the plugin folder SQLite files are relative to
     * @return the configuration
     * @throws IllegalStateException when a referenced environment variable is not set (validation reports
     *                               that first); the message names the variable only
     */
    public @NotNull HikariConfig config(@NotNull String shard, @NotNull StorageSettings settings,
            @NotNull Path dataFolder) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_PREFIX + shard);
        config.setMaximumPoolSize(settings.type() == StorageType.SQLITE ? Math.min(settings.poolSize(), 4)
                : settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(settings.connectionTimeoutMillis());
        config.setValidationTimeout(Math.min(5_000, settings.connectionTimeoutMillis()));
        // Never fail at construction: a database that is down at start is retried, not fatal.
        config.setInitializationFailTimeout(-1);
        switch (settings.type()) {
            case SQLITE -> sqlite(config, dataFolder.resolve(settings.sqliteFile()));
            case MYSQL, MARIADB -> mysql(config, settings, settings.login(environment));
            case POSTGRESQL -> postgres(config, settings, settings.login(environment));
        }
        return config;
    }

    private static void sqlite(@NotNull HikariConfig config, @NotNull Path file) {
        try {
            // SQLite creates the file but not missing folders (sqlite-file: data/hawkeye.db).
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the folder of " + file, e);
        }
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "15000");
        config.addDataSourceProperty("foreign_keys", "false");
    }

    private static void mysql(@NotNull HikariConfig config, @NotNull StorageSettings settings,
            @NotNull StorageLogin login) {
        Properties properties = credentials(login);
        properties.setProperty("cachePrepStmts", "true");
        properties.putAll(login.parameters());
        // Connector/J's connect timeout is in milliseconds.
        defaultIfAbsent(properties, "connectTimeout", Long.toString(loginTimeoutSeconds(settings) * 1000L));
        // Connector/J 4 turned TLS on by default (sslMode verify-full), which refuses any server without a
        // certificate from a trusted CA. Unless the operator chooses (sslMode, or the legacy useSsl), the
        // connection stays unencrypted as with Connector/J 3, and MySQL 8's caching_sha2_password may fetch
        // the server's RSA key to send the password over it, as the CoreProtect import does.
        if (!isPresent(properties, "sslMode") && !isPresent(properties, "useSsl")) {
            properties.setProperty("sslMode", "disable");
        }
        defaultIfAbsent(properties, "allowPublicKeyRetrieval", "true");
        // The name clashes with java.sql.Driver.
        Driver driver = new org.mariadb.jdbc.Driver();
        config.setDataSource(new RedactingDataSource(driver, "jdbc:mariadb://" + settings.host() + ":"
                + settings.effectivePort() + "/" + settings.database(), properties, login.redactor()));
    }

    private static void postgres(@NotNull HikariConfig config, @NotNull StorageSettings settings,
            @NotNull StorageLogin login) {
        Properties properties = credentials(login);
        properties.putAll(login.parameters());
        // pgJDBC's login timeout is in seconds.
        defaultIfAbsent(properties, "loginTimeout", Long.toString(loginTimeoutSeconds(settings)));
        // The name clashes with java.sql.Driver.
        Driver driver = new org.postgresql.Driver();
        config.setDataSource(new RedactingDataSource(driver, "jdbc:postgresql://" + settings.host() + ":"
                + settings.effectivePort() + "/" + settings.database(), properties, login.redactor()));
    }

    private static @NotNull Properties credentials(@NotNull StorageLogin login) {
        Properties properties = new Properties();
        properties.setProperty("user", login.username());
        properties.setProperty("password", login.password());
        return properties;
    }

    /**
     * The login timeout Hikari's own driver data source used to set ({@code DriverManager.setLoginTimeout}):
     * the connection timeout plus half a second, in whole seconds, at least one.
     */
    private static long loginTimeoutSeconds(@NotNull StorageSettings settings) {
        return Math.max(1, (500 + settings.connectionTimeoutMillis()) / 1000);
    }

    /** Sets a property unless the operator's parameters name it already, in any case. */
    private static void defaultIfAbsent(@NotNull Properties properties, @NotNull String key, @NotNull String value) {
        if (!isPresent(properties, key)) {
            properties.setProperty(key, value);
        }
    }

    /** Whether the properties name the key, in any case ({@code useSSL} is {@code useSsl}). */
    private static boolean isPresent(@NotNull Properties properties, @NotNull String key) {
        return properties.stringPropertyNames().stream()
                .anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT)));
    }
}
