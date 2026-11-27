package gg.moonrise.hawkeye.core.service.dev;

import gg.moonrise.hawkeye.support.TestPlugins;

/** Lets tests outside this package shrink a run's batches and progress interval ({@link DevDataService#tune}). */
public final class DevDataTuning {

    private DevDataTuning() {
    }

    /**
     * Tunes the running plugin's dev data service.
     *
     * @param events         events per segment, or 0 for {@code archive.segment-events}
     * @param progressMillis the progress interval
     */
    public static void tune(int events, long progressMillis) {
        TestPlugins.bean(DevDataService.class).tune(events, progressMillis);
    }
}
