package aegis.server;

/**
 * Hooks the fanout engine uses to keep the dashboard status feed current.
 * Implemented by {@link StatusFeedService}; unit tests use recording fakes.
 */
public interface StatusNotifier {
  /** An ALERT_PUSH frame was written to the device's socket (transport received). */
  void pushWritten(String deviceId);

  /** First ACK_RECEIVED for a delivery (zone acknowledged counter, monotonic). */
  void ackReceived(String deviceId);

  /** Explicit ACK_USER from a human gesture; distinct from durable receipt. */
  void ackUser(String deviceId);

  /** A delivery reached FAILED after exhausting retries. */
  void deliveryFailed(String deviceId);

  /** The active-alert set changed (publish/update/cancel/expire). */
  void alertsChanged();

  /** Device online/offline changed. */
  void connectivityChanged();
}
