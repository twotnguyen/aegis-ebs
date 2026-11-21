package aegis.client;

/**
 * Engine callbacks. Invoked from the engine's background thread — the JavaFX UI
 * marshals them with Platform.runLater; tests record them directly.
 */
public interface ClientListener {

  /** Connection state changed. */
  default void onState(AlertClientEngine.State oldState, AlertClientEngine.State newState) {}

  /**
   * An ALERT_PUSH (or SYNC_RESULT alert) was durably stored. {@code fresh=false}
   * means duplicate/stale: suppressed for display, ACK re-sent so the server stops retrying.
   */
  void onAlert(AlertRecord alert, boolean fresh);

  /** A CANCEL_PUSH tombstone was applied; remove the alert from display. */
  default void onCancel(String alertId, long revision, String reason) {}

  /** Non-fatal error (connection loss, server ERROR frame, ...). */
  default void onError(String context, Throwable error) {}
}
