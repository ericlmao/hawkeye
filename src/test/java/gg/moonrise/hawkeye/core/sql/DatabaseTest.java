package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DatabaseTest {

    @TempDir
    private Path folder;

    private SqliteHarness harness;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        harness.database().read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY)");
            }
            return null;
        });
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void aTransactionThatReturnsIsCommitted() throws SQLException {
        harness.database().transaction(DatabaseTest::insert);

        assertEquals(1, rows());
    }

    @Test
    public void anExceptionRollsTheTransactionBack() throws SQLException {
        SQLException failure = new SQLException("refused");
        assertSame(failure, assertThrows(SQLException.class, () -> harness.database().transaction(connection -> {
            insert(connection);
            throw failure;
        })));
        assertThrows(IllegalStateException.class, () -> harness.database().transaction(connection -> {
            insert(connection);
            throw new IllegalStateException("broken");
        }));

        assertEquals(0, rows());
    }

    @Test
    public void anErrorRollsTheTransactionBackBeforeAutoCommitIsRestored() throws SQLException {
        Failure failure = new Failure();
        assertSame(failure, assertThrows(Failure.class, () -> harness.database().transaction(connection -> {
            insert(connection);
            throw failure;
        })));

        assertEquals(0, rows(), "restoring auto-commit must not commit the insert");
        harness.database().transaction(DatabaseTest::insert);
        assertEquals(1, rows(), "the pooled connection still works");
    }

    @Test
    public void aTransactionThatReadsFirstHoldsTheWriteLockSoAnotherWriterWaitsInsteadOfMakingItStale()
            throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<Void> otherWrite = harness.database().transaction(connection -> {
                assertEquals(0, count(connection), "the transaction reads first");
                Future<Void> write = other.submit(() -> harness.database().read(writer -> insert(writer, 2)));
                assertThrows(TimeoutException.class, () -> write.get(500, TimeUnit.MILLISECONDS),
                        "the other writer waits for the transaction's write lock");
                insert(connection, 1);
                return write;
            });
            otherWrite.get(10, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }

        assertEquals(2, rows(), "both writes are stored");
    }

    @Test
    public void everyTransactionLeavesItsPooledConnectionInAutoCommitModeAndUsable() throws SQLException {
        harness.database().transaction(DatabaseTest::insert);
        assertThrows(IllegalStateException.class, () -> harness.database().transaction(connection -> {
            insert(connection, 2);
            throw new IllegalStateException("broken");
        }));

        for (int i = 0; i < 8; i++) {
            assertTrue(harness.database().read(Connection::getAutoCommit), "auto-commit after transaction " + i);
            int id = 10 + i;
            harness.database().transaction(connection -> insert(connection, id));
            harness.database().read(connection -> insert(connection, 100 + id));
        }
        assertEquals(17, rows(), "no transaction was left open on a connection and nothing failed stayed");
    }

    private static Void insert(@NotNull Connection connection) throws SQLException {
        return insert(connection, 1);
    }

    private static Void insert(@NotNull Connection connection, int id) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO probe (id) VALUES (" + id + ")");
        }
        return null;
    }

    private static long count(@NotNull Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM probe");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private long rows() throws SQLException {
        return harness.database().read(DatabaseTest::count);
    }

    /** An error such as running out of memory between two statements of one transaction. */
    private static final class Failure extends Error {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
