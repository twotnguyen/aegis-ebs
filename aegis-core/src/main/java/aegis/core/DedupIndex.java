package aegis.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Idempotent delivery deduplication keyed by (alertId, deviceId) with monotonic revisions.
 *
 * <p>{@link #seen} returns {@code false} on first sight of a revision newer than anything
 * recorded for that alert+device (deliver it), and {@code true} for exact duplicates and
 * stale revisions (suppress them). Repeated calls never change state after the first —
 * the index is idempotent. Thread-safe: concurrent first sights of the same key resolve
 * to exactly one {@code false}.
 */
public final class DedupIndex {

    private final ConcurrentHashMap<String, Long> highestRevision = new ConcurrentHashMap<>();

    /** {@code true} = duplicate/stale, suppress; {@code false} = first sight, deliver. */
    public boolean seen(String alertId, long revision, String deviceId) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(deviceId, "deviceId");
        String key = alertId + '\u0000' + deviceId;
        AtomicBoolean fresh = new AtomicBoolean(false);
        highestRevision.compute(key, (k, max) -> {
            if (max == null || revision > max) {
                fresh.set(true);
                return revision;
            }
            return max;
        });
        return !fresh.get();
    }
}
