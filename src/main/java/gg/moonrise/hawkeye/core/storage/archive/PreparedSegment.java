package gg.moonrise.hawkeye.core.storage.archive;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.core.storage.segment.PackedSegment;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Rows encoded as a segment but not stored yet ({@link Archive#prepare}): the compression is done with no
 * connection borrowed and no lock held, so the transaction that stores it ({@link Archive#store}) stays
 * short.
 *
 * @param rows    the rows, in the order they were given
 * @param segment the encoded segment
 */
public record PreparedSegment(@NotNull List<EventRow> rows, @NotNull PackedSegment segment) {

    /**
     * Copies the rows.
     *
     * @param rows    the rows, in the order they were given
     * @param segment the encoded segment
     */
    public PreparedSegment {
        rows = List.copyOf(rows);
    }
}
