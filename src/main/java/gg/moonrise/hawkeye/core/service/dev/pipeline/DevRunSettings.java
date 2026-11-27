package gg.moonrise.hawkeye.core.service.dev.pipeline;

import org.jetbrains.annotations.NotNull;

/**
 * What one run of fake data works with, fixed when it starts.
 *
 * @param serverName     this server's name (the run's node is named after it)
 * @param scope          the world scope the fake events are stored under
 * @param batchEvents    events per archive segment
 * @param progressMillis how often the listener hears about progress
 * @param startedMillis  when the run began
 * @param settleMillis   how long before {@code startedMillis} the events end ({@code archive.settle-seconds})
 */
public record DevRunSettings(
        @NotNull String serverName,
        @NotNull String scope,
        int batchEvents,
        long progressMillis,
        long startedMillis,
        long settleMillis) {
}
