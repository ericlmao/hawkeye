package gg.moonrise.hawkeye.entity.diagnostic;

/**
 * How many error and crash files wait to be reviewed, counted from the folder listings without reading the files.
 *
 * @param crashes the files waiting in {@code crash/}
 * @param errors  the files waiting in {@code errors/}
 */
public record PendingCount(int crashes, int errors) {

    /**
     * Whether no file waits.
     *
     * @return {@code true} when both counts are zero
     */
    public boolean isEmpty() {
        return crashes == 0 && errors == 0;
    }
}
