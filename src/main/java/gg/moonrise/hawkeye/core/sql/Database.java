package gg.moonrise.hawkeye.core.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * One shard's connection pool with its dialect and table names. Borrow connections only through
 * {@link #read} and {@link #transaction}, which always return them. On SQLite every transaction takes
 * the database's write lock when it begins ({@code BEGIN IMMEDIATE}), so a transaction that reads before
 * it writes can never find its snapshot stale; the server engines lock rows with {@code FOR UPDATE}.
 */
@Slf4j
public final class Database implements AutoCloseable {

    private final @NotNull String name;
    private final @NotNull Dialect dialect;
    private final @NotNull Tables tables;
    private final @NotNull DataSource dataSource;
    private final @NotNull AutoCloseable closer;

    /**
     * Wraps a pool.
     *
     * @param name       the shard name
     * @param dialect    the engine
     * @param tables     the table names
     * @param dataSource the pool
     * @param closer     closes the pool
     */
    public Database(@NotNull String name, @NotNull Dialect dialect, @NotNull Tables tables,
            @NotNull DataSource dataSource, @NotNull AutoCloseable closer) {
        this.name = name;
        this.dialect = dialect;
        this.tables = tables;
        this.dataSource = dataSource;
        this.closer = closer;
    }

    /**
     * The shard name.
     *
     * @return {@code home} or the configured remote shard name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * The engine.
     *
     * @return the dialect
     */
    public @NotNull Dialect dialect() {
        return dialect;
    }

    /**
     * The table names.
     *
     * @return the tables
     */
    public @NotNull Tables tables() {
        return tables;
    }

    /**
     * The dialect and table names together, as repositories take them.
     *
     * @return the SQL context
     */
    public @NotNull Sql sql() {
        return new Sql(dialect, tables);
    }

    /**
     * Runs work on a connection in auto-commit mode.
     *
     * @param work the work
     * @param <T>  the result type
     * @return the work's result
     * @throws SQLException when borrowing or the work fails
     */
    public <T> T read(@NotNull SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        }
    }

    /**
     * Runs work in one transaction: committed when it returns, rolled back when it throws anything (an
     * {@link Error} too). The connection goes back to the pool in auto-commit mode either way.
     *
     * <p>On SQLite the transaction begins with {@code BEGIN IMMEDIATE}: it waits (under the busy timeout)
     * for the write lock and takes its snapshot only once it holds it. A plain deferred {@code BEGIN}
     * would read from a snapshot first and, in WAL mode, fail its first write with
     * {@code SQLITE_BUSY_SNAPSHOT} at once whenever another connection committed meanwhile; the busy
     * timeout does not help there and a retry of the same transaction cannot succeed. The JDBC connection
     * stays in auto-commit mode around the explicit statements: sqlite-jdbc's {@code commit()} and
     * {@code rollback()} begin the next transaction straight away, which with an immediate transaction
     * mode would take the write lock again after every one. The other engines use JDBC's transaction
     * calls and roll back before auto-commit is restored (under JDBC restoring it commits an open
     * transaction).
     *
     * @param work the work
     * @param <T>  the result type
     * @return the work's result
     * @throws SQLException when borrowing, beginning (on SQLite: the write lock not free within the busy
     *                      timeout), the work or the commit fails
     */
    public <T> T transaction(@NotNull SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return dialect == Dialect.SQLITE ? immediate(connection, work) : jdbc(connection, work);
        }
    }

    /** Closes the pool; a failure is logged, not thrown, so shutdown continues. */
    @Override
    public void close() {
        try {
            closer.close();
        } catch (Exception e) {
            log.warn("Could not close the {} pool", name, e);
        }
    }

    /** A SQLite transaction by explicit statements on a connection left in auto-commit mode. */
    private static <T> T immediate(@NotNull Connection connection, @NotNull SqlWork<T> work) throws SQLException {
        execute(connection, "BEGIN IMMEDIATE");
        try {
            T result = work.run(connection);
            execute(connection, "COMMIT");
            return result;
        } catch (Throwable e) {
            try {
                execute(connection, "ROLLBACK");
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        }
    }

    private static <T> T jdbc(@NotNull Connection connection, @NotNull SqlWork<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (Throwable e) {
            rollbackQuietly(connection, e);
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void execute(@NotNull Connection connection, @NotNull String statementSql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(statementSql);
        }
    }

    private static void rollbackQuietly(@NotNull Connection connection, @NotNull Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
        }
    }
}
