package aegis.persistence;

import aegis.protocol.Priority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RecoveryScannerTest {
  @TempDir Path dir;

  @Test
  void scanReturnsOnlyActiveDeliveryStates() throws Exception {
    Db db = TestDb.fresh(dir);
    Fixtures.Base base = Fixtures.base(db, 8);
    AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
    Alert a = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
        Priority.CRITICAL, null);
    alerts.updateState(a.id(), AlertState.ACTIVE);
    DeliveryRepo repo = new DeliveryRepo(db, Fixtures.CLOCK);
    DeliveryState[] all = DeliveryState.values();
    assertEquals(8, all.length);
    for (int i = 0; i < all.length; i++) {
      Delivery d = repo.create(a.id(), 1, base.deviceIds().get(i));
      repo.updateState(d.id(), all[i], null, 0);
    }
    List<Delivery> active = new RecoveryScanner(db).scan();
    assertEquals(4, active.size());
    assertEquals(Set.of(DeliveryState.PENDING, DeliveryState.SENT,
            DeliveryState.RETRY_SCHEDULED, DeliveryState.FAILED),
        active.stream().map(Delivery::state).collect(Collectors.toSet()));
  }

  @Test
  void rebuildsSchedulerFromDiskAfterRestart() throws Exception {
    Path file = dir.resolve("restart.db");
    Db before = new Db(file);
    new MigrationRunner(before).migrate();
    Fixtures.Base base = Fixtures.base(before, 2);
    List<Delivery> firstRun = new ArrayList<>();
    TransactionalPublisher publisher = new TransactionalPublisher(before, Fixtures.CLOCK, firstRun::addAll);
    publisher.publish(new TransactionalPublisher.Request("req-restart", base.buildingId(),
        base.operatorId(), "FIRE", Priority.EMERGENCY, "Fire", "Evacuate", "{}", null,
        base.deviceIds()));
    assertEquals(2, firstRun.size());

    // Simulate process restart: brand-new Db facade + scanner over the same file.
    Db after = new Db(file);
    List<Delivery> rebuilt = new ArrayList<>();
    int recovered = new RecoveryScanner(after).recover(rebuilt::addAll);
    assertEquals(2, recovered);
    assertEquals(2, rebuilt.size());
    assertEquals(firstRun.stream().map(Delivery::id).collect(Collectors.toSet()),
        rebuilt.stream().map(Delivery::id).collect(Collectors.toSet()));
    assertTrue(rebuilt.stream().allMatch(d -> d.state() == DeliveryState.PENDING));
  }

  @Test
  void recoverIsNoOpWhenNothingActive() throws Exception {
    Db db = TestDb.fresh(dir);
    Fixtures.Base base = Fixtures.base(db, 1);
    AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
    Alert a = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
        Priority.WARNING, null);
    DeliveryRepo repo = new DeliveryRepo(db, Fixtures.CLOCK);
    Delivery d = repo.create(a.id(), 1, base.deviceIds().get(0));
    repo.updateState(d.id(), DeliveryState.ACKNOWLEDGED, null, 1);

    RecoveryScanner scanner = new RecoveryScanner(db);
    assertTrue(scanner.scan().isEmpty());
    int recovered = scanner.recover(batch -> fail("must not enqueue when nothing is active"));
    assertEquals(0, recovered);
  }

  @Test
  void recoverEnqueuesEachActiveDeliveryExactlyOnce() throws Exception {
    Db db = TestDb.fresh(dir);
    Fixtures.Base base = Fixtures.base(db, 3);
    AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
    Alert a = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
        Priority.EMERGENCY, null);
    DeliveryRepo repo = new DeliveryRepo(db, Fixtures.CLOCK);
    Delivery d0 = repo.create(a.id(), 1, base.deviceIds().get(0)); // PENDING
    Delivery d1 = repo.create(a.id(), 1, base.deviceIds().get(1));
    repo.updateState(d1.id(), DeliveryState.SENT, null, 1);
    Delivery d2 = repo.create(a.id(), 1, base.deviceIds().get(2));
    repo.updateState(d2.id(), DeliveryState.RETRY_SCHEDULED, Fixtures.NOW.plusSeconds(6), 2);

    List<Delivery> rebuilt = new ArrayList<>();
    int recovered = new RecoveryScanner(db).recover(rebuilt::addAll);
    assertEquals(3, recovered);
    assertEquals(Set.of(d0.id(), d1.id(), d2.id()),
        rebuilt.stream().map(Delivery::id).collect(Collectors.toSet()));
    assertEquals(3, rebuilt.size(), "no duplicates");
  }
}
