package aegis.persistence;

import aegis.protocol.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-01 contract (docs/reviews/server-review.md): recordAck must report whether
 * the ack row was INSERTED. Since M1 made duplicate acks an idempotent
 * {@code ON CONFLICT DO NOTHING} no-op, the UNIQUE SQLException the server used
 * to detect duplicates no longer exists — the inserted flag is the replacement
 * signal for the wire {@code duplicate} flag and the DUPLICATE audit outcome.
 */
class AckInsertedContractTest {
  @TempDir Path dir;
  Db db;
  Fixtures.Base base;
  DeliveryRepo repo;
  Alert alert;
  String dev0;
  Delivery d0;

  @BeforeEach
  void setup() throws Exception {
    db = TestDb.fresh(dir);
    base = Fixtures.base(db, 2);
    AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
    alert = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
        Priority.EMERGENCY, null);
    alerts.updateState(alert.id(), AlertState.ACTIVE);
    repo = new DeliveryRepo(db, Fixtures.CLOCK);
    dev0 = base.deviceIds().get(0);
    d0 = repo.create(alert.id(), 1, dev0);
  }

  @Test
  void firstAckInsertsAndReplayDoesNot() throws Exception {
    boolean first = repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null);
    assertTrue(first, "first RECEIVED ack must insert a device_acks row");
    boolean replay = repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null);
    assertFalse(replay, "replayed RECEIVED ack is an idempotent no-op (duplicate)");
    assertEquals(DeliveryState.RECEIVED, repo.findById(d0.id()).orElseThrow().state());
  }

  @Test
  void differentKindsEachInsertOnce() throws Exception {
    assertTrue(repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null));
    assertTrue(repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId()));
    assertFalse(repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId()));
    assertEquals(DeliveryState.ACKNOWLEDGED, repo.findById(d0.id()).orElseThrow().state());
  }

  @Test
  void mismatchedAckStillThrowsAndInsertsNothing() throws Exception {
    assertThrows(SQLException.class, () -> repo.recordAck(base.deviceIds().get(1),
        alert.id(), 1, d0.id(), AckKind.USER, base.operatorId()));
    // the rejected ack left no row: the device's own first ack still inserts
    assertTrue(repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null));
  }
}
