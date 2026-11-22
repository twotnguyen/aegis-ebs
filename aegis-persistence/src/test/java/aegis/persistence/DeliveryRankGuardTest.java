package aegis.persistence;

import aegis.protocol.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the backend-depth-audit HIGH findings H1/H2
 * (docs/reviews/backend-depth-audit.md): the dispatcher's retry/fail paths must
 * honour ACK-only-advances. {@code scheduleRetry} and {@code markFailed} are
 * guarded to the pre-ack states (PENDING/SENT/RETRY_SCHEDULED) so a late offline
 * retry or retry-budget failure can never regress a concurrent ACK
 * (RECEIVED/ACKNOWLEDGED) nor shrink the durable attempt_count.
 */
class DeliveryRankGuardTest {
  @TempDir Path dir;
  Db db;
  Fixtures.Base base;
  AlertRepo alerts;
  DeliveryRepo repo;
  Alert alert;
  String dev;

  @BeforeEach
  void setup() throws Exception {
    db = TestDb.fresh(dir);
    base = Fixtures.base(db, 2);
    alerts = new AlertRepo(db, Fixtures.CLOCK);
    alert = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
        Priority.EMERGENCY, null);
    alerts.updateState(alert.id(), AlertState.ACTIVE);
    repo = new DeliveryRepo(db, Fixtures.CLOCK);
    dev = base.deviceIds().get(0);
  }

  private Delivery fresh() throws Exception {
    return repo.create(alert.id(), 1, dev);
  }

  private Delivery reload(String id) throws Exception {
    return repo.findById(id).orElseThrow();
  }

  // ---- H1: scheduleRetry guarded to pre-ack states --------------------------

  @Test
  void scheduleRetryAdvancesPendingAndIncrementsAttempt() throws Exception {
    Delivery d = fresh();
    Instant at = Fixtures.NOW.plusSeconds(3);
    assertTrue(repo.scheduleRetry(d.id(), at));
    Delivery after = reload(d.id());
    assertEquals(DeliveryState.RETRY_SCHEDULED, after.state());
    assertEquals(d.attemptCount() + 1, after.attemptCount());
  }

  @Test
  void scheduleRetryCannotRegressReceived() throws Exception {
    Delivery d = fresh();
    repo.scheduleRetry(d.id(), Fixtures.NOW.plusSeconds(3)); // attempt_count -> 1
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.RECEIVED, null);
    Delivery received = reload(d.id());
    assertEquals(DeliveryState.RECEIVED, received.state());
    int attemptsBefore = received.attemptCount();

    // Late offline retry races the ACK: must be a benign no-op.
    assertFalse(repo.scheduleRetry(d.id(), Fixtures.NOW.plusSeconds(6)));
    Delivery after = reload(d.id());
    assertEquals(DeliveryState.RECEIVED, after.state(), "RECEIVED must not regress to RETRY_SCHEDULED");
    assertEquals(attemptsBefore, after.attemptCount(), "attempt_count must not inflate on an acked delivery");
  }

  @Test
  void scheduleRetryCannotRegressAcknowledged() throws Exception {
    Delivery d = fresh();
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.RECEIVED, null);
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.USER, "user-1");
    assertEquals(DeliveryState.ACKNOWLEDGED, reload(d.id()).state());
    assertFalse(repo.scheduleRetry(d.id(), Fixtures.NOW.plusSeconds(6)));
    assertEquals(DeliveryState.ACKNOWLEDGED, reload(d.id()).state());
  }

  // ---- H2: markFailed guarded to pre-ack states -----------------------------

  @Test
  void markFailedFailsPendingDelivery() throws Exception {
    Delivery d = fresh();
    assertTrue(repo.markFailed(d.id()));
    assertEquals(DeliveryState.FAILED, reload(d.id()).state());
  }

  @Test
  void markFailedCannotRegressReceived() throws Exception {
    Delivery d = fresh();
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.RECEIVED, null);
    int attemptsBefore = reload(d.id()).attemptCount();
    // Late retry-budget check races the ACK: must NOT fail an acked delivery.
    assertFalse(repo.markFailed(d.id()));
    Delivery after = reload(d.id());
    assertEquals(DeliveryState.RECEIVED, after.state(), "RECEIVED must not regress to FAILED");
    assertEquals(attemptsBefore, after.attemptCount(), "attempt_count must not shrink");
  }

  @Test
  void markFailedCannotRegressAcknowledged() throws Exception {
    Delivery d = fresh();
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.RECEIVED, null);
    repo.recordAck(dev, alert.id(), 1, d.id(), AckKind.USER, "user-1");
    assertFalse(repo.markFailed(d.id()));
    assertEquals(DeliveryState.ACKNOWLEDGED, reload(d.id()).state());
  }

  @Test
  void markFailedCannotOverrideCancelled() throws Exception {
    Delivery d = fresh();
    repo.updateState(d.id(), DeliveryState.CANCELLED, null, d.attemptCount());
    assertFalse(repo.markFailed(d.id()));
    assertEquals(DeliveryState.CANCELLED, reload(d.id()).state());
  }
}
