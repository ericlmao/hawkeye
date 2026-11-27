package gg.moonrise.hawkeye.core.repository.archive;

import gg.moonrise.hawkeye.core.repository.event.BoundSql;
import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.core.storage.index.place.IdRuns;
import gg.moonrise.hawkeye.core.storage.segment.ByteRange;
import gg.moonrise.hawkeye.core.storage.segment.PackedSegment;
import gg.moonrise.hawkeye.entity.storage.SegmentTotals;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The archive tables: {@code segment} (one immutable header and body of archive format 2 each, see
 * {@code core.storage.segment.Segments}) and {@code segment_flag} (the rolled-back rows of a segment as
 * {@link FlagOrdinals}). Candidates carry the header and the flags, never the body; the body is read
 * by byte range, so a lookup about one place transfers the shared frames and one or two units. Not
 * final, so tests can count what a read fetches.
 */
@SpringComponent
public class SegmentRepository {

    /** Id runs or ids per statement of {@link #ranges} and {@link #rows}. */
    public static final int RANGE_BATCH = 200;

    /** A set of more runs than this is filtered from one index scan instead of sought run by run. */
    public static final int SCATTERED_RUNS = 16;

    /** A set of more ids than this is filtered from one index scan instead of sought run by run. */
    public static final int SCATTERED_IDS = 64;

    private static final String CANDIDATE_COLUMNS = "s.id, s.first_id, s.last_id, s.events, s.header, f.ordinals";

    /**
     * Writes a segment, and its flag row when some of its rows are rolled back.
     *
     * @param connection a connection (inside the archiving transaction)
     * @param sql        the shard's SQL context
     * @param segment    the encoded segment
     * @return the new segment id
     * @throws SQLException when an insert fails
     */
    public long insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull PackedSegment segment)
            throws SQLException {
        String insert = "INSERT INTO " + sql.tables().segment() + " (first_id, last_id, events, header, body) "
                + "VALUES (" + Dialect.placeholders(5) + ")";
        long id;
        try (PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, segment.firstId());
            statement.setLong(2, segment.lastId());
            statement.setInt(3, segment.events());
            statement.setBytes(4, segment.header());
            statement.setBytes(5, segment.body());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the segment insert returned no id");
                }
                id = keys.getLong(1);
            }
        }
        if (segment.rolledBack().length > 0) {
            writeFlags(connection, sql, id, segment.rolledBack());
        }
        return id;
    }

    /**
     * A page of candidate segments for a filter: those whose id range overlaps the filter's and holds
     * ids beyond the event cursor, in reading order.
     *
     * @param connection  a connection
     * @param sql         the shard's SQL context
     * @param filter      the event filter (only its id range narrows the rows here; the header does the rest)
     * @param eventCursor the event keyset bound of the lookup (0 for none)
     * @param after       the last segment of the previous page, or {@code null} for the first page
     * @param limit       the most segments
     * @param newestFirst {@code true} to page by {@code last_id} descending, else by {@code first_id} ascending
     * @return the segments in reading order
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> candidates(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull EventFilter filter, long eventCursor, @Nullable SegmentRow after, int limit, boolean newestFirst)
            throws SQLException {
        return select(connection, candidateQuery(sql, filter, eventCursor, after, limit, newestFirst));
    }

    /**
     * The statement of {@link #candidates} (also read by the query-plan tests).
     *
     * @param sql         the shard's SQL context
     * @param filter      the event filter
     * @param eventCursor the event keyset bound (0 for none)
     * @param after       the last segment of the previous page, or {@code null}
     * @param limit       the most segments
     * @param newestFirst the reading order
     * @return the statement
     */
    public static @NotNull BoundSql candidateQuery(@NotNull Sql sql, @NotNull EventFilter filter, long eventCursor,
            @Nullable SegmentRow after, int limit, boolean newestFirst) {
        List<Object> parameters = new ArrayList<>();
        SegmentRange afterRange = after == null ? null : new SegmentRange(after.id(), after.firstId(), after.lastId());
        String where = candidateWhere(filter, eventCursor, afterRange, newestFirst, parameters);
        parameters.add(limit);
        return query(sql, where + " LIMIT ?", parameters);
    }

    /**
     * A page of the id ranges of the candidate segments of a filter, newest first: the walk of
     * {@link #candidates} without headers and flags, which the covering index on
     * {@code (last_id, id, first_id)} answers alone and in its own order. A read that names many
     * segments walks this and keeps the ones it named, so it stops where a page of events is full
     * instead of reading every segment's id range first.
     *
     * @param connection  a connection
     * @param sql         the shard's SQL context
     * @param filter      the event filter (only its id range narrows the rows)
     * @param eventCursor the event keyset bound of the lookup (0 for none)
     * @param after       the last range of the previous page, or {@code null} for the first page
     * @param limit       the most ranges
     * @return the ranges, newest first
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRange> newestRanges(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull EventFilter filter, long eventCursor, @Nullable SegmentRange after, int limit) throws SQLException {
        BoundSql query = newestRangesQuery(sql, filter, eventCursor, after, limit);
        List<SegmentRange> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new SegmentRange(rows.getLong(1), rows.getLong(2), rows.getLong(3)));
                }
            }
        }
        return out;
    }

    /**
     * The statement of {@link #newestRanges} (also read by the query-plan tests).
     *
     * @param sql         the shard's SQL context
     * @param filter      the event filter
     * @param eventCursor the event keyset bound (0 for none)
     * @param after       the last range of the previous page, or {@code null}
     * @param limit       the most ranges
     * @return the statement
     */
    public static @NotNull BoundSql newestRangesQuery(@NotNull Sql sql, @NotNull EventFilter filter, long eventCursor,
            @Nullable SegmentRange after, int limit) {
        List<Object> parameters = new ArrayList<>();
        String where = candidateWhere(filter, eventCursor, after, true, parameters);
        parameters.add(limit);
        return new BoundSql("SELECT s.id, s.first_id, s.last_id FROM " + sql.tables().segment() + " s WHERE " + where
                + " LIMIT ?", parameters);
    }

    /**
     * Whether {@link #ranges} reads a set from the covering index instead of by primary-key ranges.
     *
     * @param ids the segment ids
     * @return {@code true} for more than {@link #SCATTERED_RUNS} runs or {@link #SCATTERED_IDS} ids
     */
    public static boolean scattered(@NotNull IdRuns ids) {
        return ids.runCount() > SCATTERED_RUNS || ids.count() > SCATTERED_IDS;
    }

    /** The conditions and order of a candidate walk; the parameters are appended in order. */
    private static @NotNull String candidateWhere(@NotNull EventFilter filter, long eventCursor,
            @Nullable SegmentRange after, boolean newestFirst, @NotNull List<Object> parameters) {
        List<String> conditions = new ArrayList<>();
        conditions.add("s.last_id >= ? AND s.first_id <= ?");
        parameters.add(filter.minId());
        parameters.add(filter.maxId());
        if (eventCursor != 0) {
            conditions.add(newestFirst ? "s.first_id < ?" : "s.last_id > ?");
            parameters.add(eventCursor);
        }
        String order;
        if (newestFirst) {
            if (after != null) {
                // The first term bounds the index walk; an OR alone would start every page at the newest segment.
                conditions.add("s.last_id <= ? AND (s.last_id < ? OR s.id < ?)");
                parameters.add(after.lastId());
                parameters.add(after.lastId());
                parameters.add(after.id());
            }
            order = " ORDER BY s.last_id DESC, s.id DESC";
        } else {
            if (after != null) {
                conditions.add("s.first_id >= ? AND (s.first_id > ? OR s.id > ?)");
                parameters.add(after.firstId());
                parameters.add(after.firstId());
                parameters.add(after.id());
            }
            order = " ORDER BY s.first_id ASC, s.id ASC";
        }
        return String.join(" AND ", conditions) + order;
    }

    /**
     * The id ranges of the segments whose ids a set holds (those that still exist) and whose id range
     * overlaps {@code minId..maxId}, in id order. A set of a few ids is read by primary-key ranges, one
     * statement per {@link #RANGE_BATCH} runs. A set of more than {@link #SCATTERED_RUNS} runs or
     * {@link #SCATTERED_IDS} ids (a place active for a long time, an actor seen all the time) would be
     * thousands of seeks or drag the table's pages for a long run, so the id ranges of the segments
     * overlapping the id range are read once from the covering index on {@code (last_id, id, first_id)},
     * about 20 bytes a segment, and the set filters them.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        the segment ids
     * @param minId      the smallest event id wanted
     * @param maxId      the largest event id wanted
     * @return the ranges
     * @throws SQLException when a query fails
     */
    public @NotNull List<SegmentRange> ranges(@NotNull Connection connection, @NotNull Sql sql, @NotNull IdRuns ids,
            long minId, long maxId) throws SQLException {
        List<SegmentRange> out = new ArrayList<>();
        if (scattered(ids)) {
            BoundSql query = everyRangeQuery(sql, minId, maxId);
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                query.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong(1);
                        if (ids.contains(id)) {
                            out.add(new SegmentRange(id, rows.getLong(2), rows.getLong(3)));
                        }
                    }
                }
            }
            out.sort(Comparator.comparingLong(SegmentRange::id));
            return out;
        }
        for (int from = 0; from < ids.runCount(); from += RANGE_BATCH) {
            BoundSql query = rangeQuery(sql, ids, from, Math.min(ids.runCount(), from + RANGE_BATCH));
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                query.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        SegmentRange range = new SegmentRange(rows.getLong(1), rows.getLong(2), rows.getLong(3));
                        if (range.lastId() >= minId && range.firstId() <= maxId) {
                            out.add(range);
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(SegmentRange::id));
        return out;
    }

    /**
     * The statement that reads the id ranges of every segment overlapping an id range (also read by the
     * query-plan tests): both conditions are on columns of the {@code (last_id, id, first_id)} index, so
     * each engine answers it from the index alone.
     *
     * @param sql   the shard's SQL context
     * @param minId the smallest event id wanted
     * @param maxId the largest event id wanted
     * @return the statement
     */
    public static @NotNull BoundSql everyRangeQuery(@NotNull Sql sql, long minId, long maxId) {
        return new BoundSql("SELECT id, first_id, last_id FROM " + sql.tables().segment()
                + " WHERE last_id >= ? AND first_id <= ?", List.of(minId, maxId));
    }

    /**
     * The statement of one batch of {@link #ranges} (also read by the query-plan tests).
     *
     * @param sql  the shard's SQL context
     * @param ids  the segment ids
     * @param from the first run of the batch
     * @param to   the run after the last
     * @return the statement
     */
    public static @NotNull BoundSql rangeQuery(@NotNull Sql sql, @NotNull IdRuns ids, int from, int to) {
        List<Object> parameters = new ArrayList<>();
        StringJoiner terms = new StringJoiner(" OR ");
        for (int run = from; run < to; run++) {
            if (ids.start(run) == ids.end(run)) {
                terms.add("id = ?");
                parameters.add(ids.start(run));
            } else {
                terms.add("id BETWEEN ? AND ?");
                parameters.add(ids.start(run));
                parameters.add(ids.end(run));
            }
        }
        return new BoundSql("SELECT id, first_id, last_id FROM " + sql.tables().segment() + " WHERE " + terms,
                parameters);
    }

    /**
     * Candidate rows (header and flags, never the body) of some segments.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        segment ids
     * @return the rows of those that exist, in the order of {@code ids}
     * @throws SQLException when a query fails
     */
    public @NotNull List<SegmentRow> rows(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<Long> ids)
            throws SQLException {
        Map<Long, SegmentRow> found = new HashMap<>();
        for (int from = 0; from < ids.size(); from += RANGE_BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + RANGE_BATCH));
            for (SegmentRow row : select(connection, rowsQuery(sql, batch))) {
                found.put(row.id(), row);
            }
        }
        List<SegmentRow> out = new ArrayList<>(ids.size());
        for (long id : ids) {
            SegmentRow row = found.get(id);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * The statement of one batch of {@link #rows} (also read by the query-plan tests).
     *
     * @param sql the shard's SQL context
     * @param ids segment ids, at least one
     * @return the statement
     */
    public static @NotNull BoundSql rowsQuery(@NotNull Sql sql, @NotNull List<Long> ids) {
        return query(sql, "s.id IN (" + Dialect.placeholders(ids.size()) + ")", new ArrayList<>(ids));
    }

    /**
     * The ids of the newest segments below an id, newest first (primary key order).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive segment id bound
     * @param limit      the most ids
     * @return the ids, descending
     * @throws SQLException when the query fails
     */
    public @NotNull List<Long> idsBelow(@NotNull Connection connection, @NotNull Sql sql, long belowId, int limit)
            throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + sql.tables().segment()
                + " WHERE id < ? ORDER BY id DESC LIMIT ?")) {
            statement.setLong(1, belowId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(rows.getLong(1));
                }
            }
        }
        return out;
    }

    /**
     * The smallest segment id stored (the first primary key entry).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the archive is empty
     * @throws SQLException when the query fails
     */
    public long oldestSegmentId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MIN(id) FROM "
                + sql.tables().segment());
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * Segments holding events older than an id, for purging, oldest first.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param after      the last segment of the previous page, or {@code null} for the first page
     * @param limit      the most segments
     * @return the segments by {@code first_id}, then id
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> olderThan(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @Nullable SegmentRow after, int limit) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        parameters.add(belowId);
        String keyset = "";
        if (after != null) {
            keyset = " AND (s.first_id > ? OR (s.first_id = ? AND s.id > ?))";
            parameters.add(after.firstId());
            parameters.add(after.firstId());
            parameters.add(after.id());
        }
        parameters.add(limit);
        return select(connection, query(sql, "s.first_id < ?" + keyset + " ORDER BY s.first_id ASC, s.id ASC LIMIT ?",
                parameters));
    }

    /**
     * Reads byte ranges of a segment body in one statement. Ranges that touch or overlap are fetched as
     * one and split again.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @param ranges     the ranges, each inside the body
     * @return the bytes of each range, in the order given
     * @throws SQLException when the query fails, the segment is gone or the body is shorter than a range
     */
    public @NotNull List<byte[]> readRanges(@NotNull Connection connection, @NotNull Sql sql, long segment,
            @NotNull List<ByteRange> ranges) throws SQLException {
        if (ranges.isEmpty()) {
            return List.of();
        }
        List<ByteRange> fetched = merge(ranges);
        StringJoiner columns = new StringJoiner(", ");
        fetched.forEach(range -> columns.add("substr(body, ?, ?)"));
        byte[][] slices = new byte[fetched.size()][];
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + columns + " FROM "
                + sql.tables().segment() + " WHERE id = ?")) {
            int parameter = 1;
            for (ByteRange range : fetched) {
                statement.setInt(parameter++, range.offset() + 1);
                statement.setInt(parameter++, range.length());
            }
            statement.setLong(parameter, segment);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("segment " + segment + " does not exist");
                }
                for (int i = 0; i < slices.length; i++) {
                    byte[] bytes = rows.getBytes(i + 1);
                    if (bytes == null || bytes.length != fetched.get(i).length()) {
                        throw new SQLException("segment " + segment + " is shorter than its header says");
                    }
                    slices[i] = bytes;
                }
            }
        }
        List<byte[]> out = new ArrayList<>(ranges.size());
        for (ByteRange range : ranges) {
            int index = containing(fetched, range);
            ByteRange slice = fetched.get(index);
            int from = range.offset() - slice.offset();
            out.add(Arrays.copyOfRange(slices[index], from, from + range.length()));
        }
        return out;
    }

    /**
     * A segment's rolled-back ordinals, locking the segment row against a concurrent flag change or
     * rewrite until the transaction ends (MySQL and PostgreSQL; on SQLite the transaction already holds the
     * database's write lock from its {@code BEGIN IMMEDIATE}, see {@code Database#transaction}).
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @return the ordinals, ascending; empty when the segment no longer exists
     * @throws SQLException when the query fails
     */
    public @NotNull Optional<int[]> flagsForUpdate(@NotNull Connection connection, @NotNull Sql sql, long segment)
            throws SQLException {
        String lock = sql.dialect() == Dialect.SQLITE ? "" : " FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + sql.tables().segment()
                + " WHERE id = ?" + lock)) {
            statement.setLong(1, segment);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT ordinals FROM "
                + sql.tables().segmentFlag() + " WHERE segment = ?")) {
            statement.setLong(1, segment);
            try (ResultSet rows = statement.executeQuery()) {
                return Optional.of(rows.next() ? FlagOrdinals.decode(rows.getBytes(1)) : new int[0]);
            }
        }
    }

    /**
     * Replaces a segment's rolled-back ordinals; an empty set deletes the flag row.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @param ordinals   the ordinals, strictly ascending
     * @throws SQLException when the statement fails
     */
    public void writeFlags(@NotNull Connection connection, @NotNull Sql sql, long segment, int @NotNull [] ordinals)
            throws SQLException {
        if (ordinals.length == 0) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segmentFlag()
                    + " WHERE segment = ?")) {
                statement.setLong(1, segment);
                statement.executeUpdate();
            }
            return;
        }
        String upsert = sql.dialect().upsert(sql.tables().segmentFlag(), List.of("segment", "ordinals"), "segment",
                List.of("ordinals"), 1);
        try (PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setLong(1, segment);
            statement.setBytes(2, FlagOrdinals.encode(ordinals));
            statement.executeUpdate();
        }
    }

    /**
     * Deletes segments and their flag rows.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        segment ids
     * @return the number of segments deleted
     * @throws SQLException when a delete fails
     */
    public int delete(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids)
            throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        List<Long> values = List.copyOf(ids);
        String in = " IN (" + Dialect.placeholders(values.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segmentFlag()
                + " WHERE segment" + in)) {
            bindLongs(statement, values);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segment()
                + " WHERE id" + in)) {
            bindLongs(statement, values);
            return statement.executeUpdate();
        }
    }

    /**
     * The largest id any segment holds (an index lookup on {@code last_id}).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the archive is empty
     * @throws SQLException when the query fails
     */
    public long newestId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(last_id) FROM "
                + sql.tables().segment());
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * The number of archived events and segments, and the bytes they take.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the totals
     * @throws SQLException when the query fails
     */
    public @NotNull SegmentTotals totals(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(events), 0), COUNT(*), "
                + "COALESCE(SUM(LENGTH(header) + LENGTH(body)), 0) FROM " + sql.tables().segment());
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return new SegmentTotals(rows.getLong(1), rows.getLong(2), rows.getLong(3));
        }
    }

    private static @NotNull BoundSql query(@NotNull Sql sql, @NotNull String where, @NotNull List<Object> parameters) {
        return new BoundSql("SELECT " + CANDIDATE_COLUMNS + " FROM " + sql.tables().segment() + " s LEFT JOIN "
                + sql.tables().segmentFlag() + " f ON f.segment = s.id WHERE " + where, parameters);
    }

    private static @NotNull List<SegmentRow> select(@NotNull Connection connection, @NotNull BoundSql query)
            throws SQLException {
        List<SegmentRow> segments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] flags = rows.getBytes(6);
                    segments.add(new SegmentRow(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getInt(4),
                            rows.getBytes(5), flags == null ? new int[0] : FlagOrdinals.decode(flags)));
                }
            }
        }
        return segments;
    }

    /** The ranges sorted by offset, with touching and overlapping ones joined. */
    private static @NotNull List<ByteRange> merge(@NotNull List<ByteRange> ranges) {
        List<ByteRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(ByteRange::offset));
        List<ByteRange> merged = new ArrayList<>();
        ByteRange current = sorted.getFirst();
        for (ByteRange next : sorted.subList(1, sorted.size())) {
            if (next.offset() <= current.end()) {
                int end = Math.max(current.end(), next.end());
                current = new ByteRange(current.offset(), end - current.offset());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static int containing(@NotNull List<ByteRange> fetched, @NotNull ByteRange range) {
        for (int i = 0; i < fetched.size(); i++) {
            ByteRange slice = fetched.get(i);
            if (range.offset() >= slice.offset() && range.end() <= slice.end()) {
                return i;
            }
        }
        throw new IllegalStateException("no fetched slice holds " + range);
    }

    private static void bindLongs(@NotNull PreparedStatement statement, @NotNull List<Long> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            statement.setLong(i + 1, values.get(i));
        }
    }
}
