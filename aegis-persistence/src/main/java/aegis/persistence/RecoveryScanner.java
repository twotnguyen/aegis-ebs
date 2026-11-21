package aegis.persistence;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Crash recovery: scans deliveries still owned by the scheduler
 * (PENDING / SENT / RETRY_SCHEDULED / FAILED) so the scheduler can be rebuilt
 * from durable state after a restart. Read-only — state transitions remain the
 * scheduler's job.
 */
public final class RecoveryScanner {
  private final DeliveryRepo deliveries;

  public RecoveryScanner(Db db) {
    this(new DeliveryRepo(db));
  }

  public RecoveryScanner(DeliveryRepo deliveries) {
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
  }

  /** All active deliveries, oldest first. */
  public List<Delivery> scan() throws SQLException {
    return deliveries.findActive();
  }

  /**
   * Rebuilds the scheduler by enqueueing every active delivery exactly once.
   *
   * @return number of deliveries handed to the enqueuer (0 = nothing to do)
   */
  public int recover(DeliveryEnqueuer enqueuer) throws SQLException {
    Objects.requireNonNull(enqueuer, "enqueuer");
    List<Delivery> active = scan();
    if (!active.isEmpty()) {
      enqueuer.enqueue(List.copyOf(active));
    }
    return active.size();
  }
}
