package aegis.persistence;

import java.util.List;

/**
 * Sink for deliveries that are ready to be scheduled. Invoked ONLY after the
 * publishing transaction has committed (or by {@link RecoveryScanner} when
 * rebuilding the scheduler after a restart).
 */
@FunctionalInterface
public interface DeliveryEnqueuer {
  void enqueue(List<Delivery> deliveries);
}
