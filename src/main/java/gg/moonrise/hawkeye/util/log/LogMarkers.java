package gg.moonrise.hawkeye.util.log;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * SLF4J markers that tell the error log ({@code core.diagnostic}) how to file a logged problem. SLF4J hands them
 * to the server's log4j by name, so the error log's filter matches the names.
 */
public final class LogMarkers {

    /** The name of {@link #CRASH}. */
    public static final String CRASH_NAME = "HAWKEYE_CRASH";

    /** The name of {@link #REPORTED}. */
    public static final String REPORTED_NAME = "HAWKEYE_REPORTED";

    /**
     * A crash: Hawkeye could not start, or one of its threads stopped. Filed under {@code crash/} instead of
     * {@code errors/}.
     */
    public static final Marker CRASH = MarkerFactory.getMarker(CRASH_NAME);

    /**
     * A line the error log already handled (its one console line for a problem, or its own failure): printed
     * as it is, never filed again.
     */
    public static final Marker REPORTED = MarkerFactory.getMarker(REPORTED_NAME);

    private LogMarkers() {
    }
}
