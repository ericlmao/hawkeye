package gg.moonrise.hawkeye.core.repository.event;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Reads and writes the {@code event} table on a borrowed connection. Stateless: the caller owns the
 * connection and the transaction.
 */
@SpringComponent
public final class EventRepository {

    /** Rows per multi-row insert (10 columns each, well inside every engine's parameter limit). */
    public static final int ROWS_PER_STATEMENT = 500;

    /** Ids per {@code IN} list of a flag update. */
    public static final int IDS_PER_STATEMENT = 500;

    /**
     * Inserts events, skipping ids that already exist (so retrying a batch whose commit result was lost
     * is harmless).
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param rows       the rows
     * @return the number of rows inserted
     * @throws SQLException when a statement fails
     */
    public int insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<EventRow> rows)
            throws SQLException {
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            List<EventRow> chunk = rows.subList(from, Math.min(rows.size(), from + ROWS_PER_STATEMENT));
            String statementSql = sql.dialect().insertIgnoringDuplicates(sql.tables().event(), EventSql.COLUMNS,
                    chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                int index = 1;
                for (EventRow row : chunk) {
                    index = bind(statement, index, row);
                }
                inserted += statement.executeUpdate();
            }
        }
        return inserted;
    }

    /**
     * Reads a page of events.
     *
     * @param connection  the connection
     * @param sql         the shard's SQL context
     * @param filter      the filter
     * @param cursor      the exclusive keyset bound, 0 for the first page
     * @param limit       the most rows
     * @param newestFirst the order
     * @return the rows in that order
     * @throws SQLException when the query fails
     */
    public @NotNull List<EventRow> find(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter,
            long cursor, int limit, boolean newestFirst) throws SQLException {
        BoundSql query = EventSql.select(sql.tables(), filter, cursor, limit, newestFirst);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> out = new ArrayList<>(Math.min(limit, 1024));
                while (rows.next()) {
                    out.add(read(rows));
                }
                return out;
            }
        }
    }

    /**
     * Counts matching events.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param filter     the filter
     * @return the count
     * @throws SQLException when the query fails
     */
    public long count(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter)
            throws SQLException {
        BoundSql query = EventSql.count(sql.tables(), filter);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /**
     * The largest id in the hot table (an index lookup on the primary key).
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the table is empty
     * @throws SQLException when the query fails
     */
    public long newestId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(id) FROM " + sql.tables().event());
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * The smallest id in the hot table (an index lookup on the primary key).
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the table is empty
     * @throws SQLException when the query fails
     */
    public long oldestId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MIN(id) FROM " + sql.tables().event());
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * Whether at least {@code count} events lie below an id: one step along the primary key past the
     * first {@code count - 1} of them, never a read of the rows.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param count      how many events, at least 1
     * @return {@code true} when there are that many
     * @throws SQLException when the query fails
     */
    public boolean hasAtLeast(@NotNull Connection connection, @NotNull Sql sql, long belowId, int count)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + sql.tables().event()
                + " WHERE id < ? ORDER BY id LIMIT 1 OFFSET ?")) {
            statement.setLong(1, belowId);
            statement.setInt(2, count - 1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Sets or clears the rolled-back flag.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param ids        the event ids
     * @param rolledBack the new value
     * @return the number of rows updated
     * @throws SQLException when a statement fails
     */
    public int setRolledBack(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids,
            boolean rolledBack) throws SQLException {
        List<Long> all = List.copyOf(ids);
        int updated = 0;
        for (int from = 0; from < all.size(); from += IDS_PER_STATEMENT) {
            BoundSql update = EventSql.setRolledBack(sql.tables(),
                    all.subList(from, Math.min(all.size(), from + IDS_PER_STATEMENT)), rolledBack);
            try (PreparedStatement statement = connection.prepareStatement(update.sql())) {
                update.bind(statement);
                updated += statement.executeUpdate();
            }
        }
        return updated;
    }

    /**
     * Deletes up to {@code limit} of the oldest events below an id.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive upper id bound
     * @param worlds     world ids to purge, or empty for every world
     * @param limit      the most rows
     * @return the number of rows deleted
     * @throws SQLException when the statement fails
     */
    public int deleteBatch(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @NotNull Collection<Integer> worlds, int limit) throws SQLException {
        BoundSql delete = EventSql.deleteBatch(sql.dialect(), sql.tables(), belowId, worlds, limit);
        try (PreparedStatement statement = connection.prepareStatement(delete.sql())) {
            delete.bind(statement);
            return statement.executeUpdate();
        }
    }

    /**
     * The oldest events below an id, read without a lock: an archiving pass reads them in auto-commit
     * mode, encodes them, and checks them with {@link #lockUnchanged} inside the transaction that stores
     * the segment and deletes them.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param limit      the most rows
     * @return the rows, oldest first
     * @throws SQLException when the query fails
     */
    public @NotNull List<EventRow> oldestForArchive(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + String.join(", ", EventSql.COLUMNS)
                + " FROM " + sql.tables().event() + " WHERE id < ? ORDER BY id LIMIT ?")) {
            statement.setLong(1, belowId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> out = new ArrayList<>(Math.min(limit, 1 << 16));
                while (rows.next()) {
                    out.add(read(rows));
                }
                return out;
            }
        }
    }

    /**
     * Locks the hot rows in the id range of rows read earlier and tells whether every one of those rows is
     * still there with the same flags, the only column a statement updates ({@link #setRolledBack}). Rows
     * in the range that were not read (written later) are ignored. MySQL and PostgreSQL lock with
     * {@code FOR UPDATE} until the transaction ends; on SQLite the transaction already holds the database's
     * write lock ({@code Database#transaction}).
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @param rows       the rows read earlier, with distinct ids
     * @return {@code true} when none of them was deleted or changed
     * @throws SQLException when the query fails
     */
    public boolean lockUnchanged(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<EventRow> rows)
            throws SQLException {
        if (rows.isEmpty()) {
            return true;
        }
        List<EventRow> sorted = rows.stream().sorted(Comparator.comparingLong(EventRow::id)).toList();
        String lock = sql.dialect() == Dialect.SQLITE ? "" : " FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, flags FROM " + sql.tables().event()
                + " WHERE id BETWEEN ? AND ? ORDER BY id" + lock)) {
            statement.setLong(1, sorted.getFirst().id());
            statement.setLong(2, sorted.getLast().id());
            try (ResultSet stored = statement.executeQuery()) {
                int next = 0;
                while (stored.next() && next < sorted.size()) {
                    long id = stored.getLong(1);
                    EventRow expected = sorted.get(next);
                    if (id > expected.id()) {
                        return false;
                    }
                    if (id == expected.id()) {
                        if (stored.getInt(2) != expected.flags()) {
                            return false;
                        }
                        next++;
                    }
                }
                return next == sorted.size();
            }
        }
    }

    /**
     * Deletes events by id.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        the event ids
     * @return the number deleted
     * @throws SQLException when a statement fails
     */
    public int deleteIds(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids)
            throws SQLException {
        List<Long> all = List.copyOf(ids);
        int deleted = 0;
        for (int from = 0; from < all.size(); from += IDS_PER_STATEMENT) {
            List<Long> chunk = all.subList(from, Math.min(all.size(), from + IDS_PER_STATEMENT));
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().event()
                    + " WHERE id IN (" + Dialect.placeholders(chunk.size()) + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setLong(i + 1, chunk.get(i));
                }
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    private static int bind(@NotNull PreparedStatement statement, int start, @NotNull EventRow row)
            throws SQLException {
        int index = start;
        statement.setLong(index++, row.id());
        statement.setInt(index++, row.world());
        statement.setLong(index++, row.position());
        statement.setInt(index++, row.actor());
        statement.setInt(index++, row.action());
        statement.setInt(index++, row.subject());
        setOptionalInt(statement, index++, row.secondary());
        setOptionalInt(statement, index++, row.amount());
        statement.setInt(index++, row.flags());
        if (row.data() == null) {
            statement.setNull(index++, Types.BINARY);
        } else {
            statement.setBytes(index++, row.data());
        }
        return index;
    }

    private static void setOptionalInt(@NotNull PreparedStatement statement, int index, int value)
            throws SQLException {
        if (value == 0) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static @NotNull EventRow read(@NotNull ResultSet rows) throws SQLException {
        return new EventRow(rows.getLong(1), rows.getInt(2), rows.getLong(3), rows.getInt(4), rows.getInt(5),
                rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getBytes(10));
    }
}
