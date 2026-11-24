package aegis.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks expected acknowledgements per alert revision (pure in-memory logic,
 * thread-safe). A revision-1 convenience overload keeps older callers simple;
 * the server uses the revision-aware API so late ACKs for different revisions
 * can never complete each other.
 */
public final class AckLedger {

    private record Key(String alertId, int revision) {}

    private static final class Entry {
        final Set<String> expected;
        final Set<String> acked = new HashSet<>();

        Entry(Set<String> expected) {
            this.expected = expected;
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();

    /** Registers the expected clients for revision 1 of {@code alertId}. */
    public synchronized void open(String alertId, Collection<String> clientIds) {
        open(alertId, 1, clientIds);
    }

    /** Registers the expected clients for one revision. Throws if already open. */
    public synchronized void open(String alertId, int revision, Collection<String> clientIds) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(clientIds, "clientIds");
        Key key = new Key(alertId, revision);
        if (entries.containsKey(key)) {
            throw new IllegalStateException("alert revision already open: " + key);
        }
        Set<String> expected = new HashSet<>();
        for (String clientId : clientIds) {
            expected.add(Objects.requireNonNull(clientId, "clientId"));
        }
        entries.put(key, new Entry(Collections.unmodifiableSet(expected)));
    }

    /** Records an ACK for revision 1; true only when state newly advanced. */
    public synchronized boolean ack(String alertId, String clientId) {
        return ack(alertId, 1, clientId);
    }

    /** Records an ACK for a revision; true only when state newly advanced. */
    public synchronized boolean ack(String alertId, int revision, String clientId) {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(clientId, "clientId");
        Entry entry = entries.get(new Key(alertId, revision));
        if (entry == null || !entry.expected.contains(clientId)) {
            return false;
        }
        return entry.acked.add(clientId);
    }

    /** Unmodifiable missing set for revision 1. */
    public synchronized Set<String> missing(String alertId) {
        return missing(alertId, 1);
    }

    /** Unmodifiable missing set for one revision; empty for unknown revisions. */
    public synchronized Set<String> missing(String alertId, int revision) {
        Objects.requireNonNull(alertId, "alertId");
        Entry entry = entries.get(new Key(alertId, revision));
        if (entry == null) {
            return Set.of();
        }
        Set<String> remaining = new HashSet<>(entry.expected);
        remaining.removeAll(entry.acked);
        return Collections.unmodifiableSet(remaining);
    }

    /** {@code true} when revision 1 is fully acknowledged. */
    public synchronized boolean complete(String alertId) {
        return complete(alertId, 1);
    }

    /** {@code true} when all expected clients for this revision acked. */
    public synchronized boolean complete(String alertId, int revision) {
        Objects.requireNonNull(alertId, "alertId");
        Entry entry = entries.get(new Key(alertId, revision));
        return entry != null && entry.acked.containsAll(entry.expected);
    }
}
