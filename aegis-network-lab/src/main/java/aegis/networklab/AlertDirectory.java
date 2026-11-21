package aegis.networklab;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory authoritative alert store for the lab TLS endpoint. The real aegis-server module
 * can replace this with its persistence-backed directory; the wire contract (EBSP
 * SYNC_REQUEST/SYNC_RESULT) stays the same.
 */
public final class AlertDirectory {
  private final Map<String, AlertContent> alerts = new ConcurrentHashMap<>();

  public void put(AlertContent content) {
    alerts.merge(content.alertId(), content,
        (old, cur) -> cur.revision() >= old.revision() ? cur : old);
  }

  public Optional<AlertContent> find(String alertId) {
    return Optional.ofNullable(alerts.get(alertId));
  }

  public int size() { return alerts.size(); }
}
