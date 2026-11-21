package aegis.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks expected acknowledgements per alert (pure in-memory logic, thread-safe).
 *
 * <p>Semantics (EBSP/2.0: "ACK only advances state"):
 * <ul>
 *   <li>{@link #open} registers the expected client set once per alertId; the input
 *       collection is defensively copied and deduplicated. Re-opening an alert throws
 *       {@link IllegalStateException}.</li>
 *   <li>{@link #ack} returns {@code true} only when it newly advances state. Repeat acks
 *       and acks from clients outside the expected set are ignored ({@code false}).</li>
 *   <li>{@link #missing} returns an unmodifiable snapshot of expected-minus-acked;
 *       unknown alerts yield an empty set.</li>
 *   <li>{@link #complete} is {@code true} when every expected client has acked
 *       (an empty expected set completes immediately); unknown alerts are not complete.</li>
 * </ul>
 */
public final class AckLedger {

    private static final class Entry {
        final Set<String> expected;
        final Set<String> acked = new HashSet<>();

        Entry(Set<String> expected) {
            this.expected = expected;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** Registers the expected clients for {@code alertId}. Throws if already open. */
    public synchronized void open(String alertId, Collection<String> clientIds) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(clientIds, "clientIds");
        if (entries.containsKey(alertId)) {
            throw new IllegalStateException("alert already open: " + alertId);
        }
        Set<String> expected = new HashSet<>();
        for (String clientId : clientIds) {
            expected.add(Objects.requireNonNull(clientId, "clientId"));
        }
        entries.put(alertId, new Entry(Collections.unmodifiableSet(expected)));
    }

    /** Records an ack; {@code true} only if state newly advanced. Idempotent. */
    public synchronized boolean ack(String alertId, String clientId) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(clientId, "clientId");
        Entry entry = entries.get(alertId);
        if (entry == null || !entry.expected.contains(clientId)) {
            return false;
        }
        return entry.acked.add(clientId);
    }

    /** Unmodifiable set of expected clients that have not acked yet; empty for unknown alerts. */
    public synchronized Set<String> missing(String alertId) {
        Objects.requireNonNull(alertId, "alertId");
        Entry entry = entries.get(alertId);
        if (entry == null) {
            return Set.of();
        }
        Set<String> remaining = new HashSet<>(entry.expected);
        remaining.removeAll(entry.acked);
        return Collections.unmodifiableSet(remaining);
    }

    /** {@code true} when all expected clients acked; {@code false} for unknown alerts. */
    public synchronized boolean complete(String alertId) {
        Objects.requireNonNull(alertId, "alertId");
        Entry entry = entries.get(alertId);
        return entry != null && entry.acked.containsAll(entry.expected);
    }
}
