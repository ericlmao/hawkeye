package gg.moonrise.hawkeye.core.repository.index;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The segment index's state: {@code segment_index}, one row whose {@code indexed_from} says from which
 * segment id on every segment is in the index (places and actors alike). Every change to an index table
 * locks this row first, so two writers never rewrite the same index row from the same old version.
 */
@SpringComponent
public final class SegmentIndexRepository {

    /**
     * The segment id from which on every segment is indexed, without locking.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the value; empty when the state row is missing
     * @throws SQLException when the query fails
     */
    public @NotNull Optional<Long> indexedFrom(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        return readState(connection, sql, "");
    }

    /**
     * The segment id from which on every segment is indexed, locking the state row until the transaction
     * ends (MySQL and PostgreSQL; on SQLite a plain read, since the transaction already holds the database's
     * write lock from its {@code BEGIN IMMEDIATE}, see {@code Database#transaction}).
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @return the value
     * @throws SQLException when the query fails or the state row is missing
     */
    public long lockIndexedFrom(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        String lock = sql.dialect() == Dialect.SQLITE ? "" : " FOR UPDATE";
        return readState(connection, sql, lock)
                .orElseThrow(() -> new SQLException("the segment index has no state row"));
    }

    /**
     * Sets {@code indexed_from}.
     *
     * @param connection a connection inside the transaction that holds the state lock
     * @param sql        the shard's SQL context
     * @param value      the new value
     * @throws SQLException when the statement fails
     */
    public void setIndexedFrom(@NotNull Connection connection, @NotNull Sql sql, long value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().segmentIndex()
                + " SET indexed_from = ? WHERE id = 1")) {
            statement.setLong(1, value);
            statement.executeUpdate();
        }
    }

    /**
     * Empties every index table and marks every stored segment as not indexed, so catching up rebuilds
     * the index.
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @throws SQLException when a statement fails
     */
    public void reset(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        lockIndexedFrom(connection, sql);
        for (String table : List.of(sql.tables().regionSegment(), sql.tables().blockSegment(),
                sql.tables().actorSegment())) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().segmentIndex()
                + " SET indexed_from = (SELECT COALESCE(MAX(id), 0) + 1 FROM " + sql.tables().segment()
                + ") WHERE id = 1")) {
            statement.executeUpdate();
        }
    }

    private static @NotNull Optional<Long> readState(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT indexed_from FROM "
                + sql.tables().segmentIndex() + " WHERE id = 1" + lock);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(rows.getLong(1)) : Optional.empty();
        }
    }
}
