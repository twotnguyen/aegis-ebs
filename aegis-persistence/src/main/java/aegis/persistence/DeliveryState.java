package aegis.persistence;

/** Delivery lifecycle states (deliveries.state). */
public enum DeliveryState {
  PENDING, SENT, RECEIVED, ACKNOWLEDGED, RETRY_SCHEDULED, FAILED, EXPIRED, CANCELLED;

  /** States the scheduler still owns after a restart (RecoveryScanner). */
  public boolean isActive() {
    return this == PENDING || this == SENT || this == RETRY_SCHEDULED || this == FAILED;
  }
}
