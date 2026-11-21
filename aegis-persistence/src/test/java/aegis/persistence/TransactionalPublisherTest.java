package aegis.persistence;

import aegis.protocol.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalPublisherTest {
  @TempDir Path dir;
  Db db;
  Fixtures.Base base;
  List<List<Delivery>> batches;
  TransactionalPublisher publisher;

  @BeforeEach
  void setup() throws Exception {
    db = TestDb.fresh(dir);
    base = Fixtures.base(db, 2);
    batches = Collections.synchronizedList(new ArrayList<>());
    publisher = new TransactionalPublisher(db, Fixtures.CLOCK, batches::add);
  }

  private TransactionalPublisher.Request request(String requestId, List<String> deviceIds) {
    return new TransactionalPublisher.Request(requestId, base.buildingId(), base.operatorId(),
        "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}",
        Fixtures.NOW.plusSeconds(3600), deviceIds);
  }

  private int count(String table) throws SQLException {
    try (Connection c = db.open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

  @Test
  void publishCommitsAllRowsAndEnqueuesOnlyAfterCommit() throws Exception {
    List<Delivery> enqueued = new ArrayList<>();
    AtomicInteger visibleDeliveriesAtEnqueueTime = new AtomicInteger(-1);
    TransactionalPublisher p = new TransactionalPublisher(db, Fixtures.CLOCK, batch -> {
      enqueued.addAll(batch);
      // A separate connection only sees the rows if COMMIT already happened.
      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery("SELECT count(*) FROM deliveries")) {
        assertTrue(rs.next());
        visibleDeliveriesAtEnqueueTime.set(rs.getInt(1));
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    });

    TransactionalPublisher.Result result = p.publish(request("req-1", base.deviceIds()));

    Alert a = result.alert();
    assertEquals(AlertState.ACTIVE, a.state());
    assertEquals(1, a.currentRevision());
    assertEquals(Priority.EMERGENCY, a.priority());
    assertEquals(Fixtures.NOW, a.createdAt());
    assertEquals(Fixtures.NOW.plusSeconds(3600), a.expiresAt());
    UUID.fromString(a.id());

    assertEquals("Fire on floor 1", result.revision().title());
    assertEquals("Evacuate now", result.revision().body());
    assertEquals(1, result.revision().revision());

    assertEquals(1, count("alerts"));
    assertEquals(1, count("alert_revisions"));
    assertEquals(2, count("alert_targets"));
    assertEquals(2, count("deliveries"));
    assertEquals(1, count("alert_operations"));

    // Target snapshot matches the requested devices at revision 1.
    AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
    assertEquals(Set.copyOf(base.deviceIds()),
        alerts.targets(a.id(), 1).stream().map(AlertTarget::deviceId).collect(Collectors.toSet()));

    // Deliveries: PENDING, attempt 0, stable ids, correct alert/revision/device.
    assertEquals(2, result.deliveries().size());
    for (Delivery d : result.deliveries()) {
      assertEquals(DeliveryState.PENDING, d.state());
      assertEquals(0, d.attemptCount());
      assertEquals(a.id(), d.alertId());
      assertEquals(1, d.revision());
      UUID.fromString(d.id());
    }
    assertEquals(Set.copyOf(base.deviceIds()),
        result.deliveries().stream().map(Delivery::deviceId).collect(Collectors.toSet()));

    // Enqueue happened exactly once, after commit.
    assertEquals(2, enqueued.size());
    assertEquals(result.deliveries(), enqueued);
    assertEquals(2, visibleDeliveriesAtEnqueueTime.get(),
        "deliveries must be committed before enqueue (other connection saw them)");

    try (Connection c = db.open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT state, request_id FROM alert_operations WHERE alert_id='" + a.id() + "'")) {
      assertTrue(rs.next());
      assertEquals("DONE", rs.getString(1));
      assertEquals("req-1", rs.getString(2));
    }
  }

  @Test
  void rollsBackEverythingOnUnknownDevice() throws Exception {
    List<String> devices = new ArrayList<>(base.deviceIds());
    devices.add(UUID.randomUUID().toString()); // FK violation mid-transaction
    assertThrows(SQLException.class, () -> publisher.publish(request("req-bad", devices)));
    assertEquals(0, count("alerts"));
    assertEquals(0, count("alert_revisions"));
    assertEquals(0, count("alert_targets"));
    assertEquals(0, count("deliveries"));
    assertEquals(0, count("alert_operations"));
    assertTrue(batches.isEmpty(), "nothing may be enqueued after rollback");
  }

  @Test
  void rollsBackOnDuplicateRequestId() throws Exception {
    publisher.publish(request("req-dup", base.deviceIds()));
    assertEquals(1, batches.size());
    assertThrows(SQLException.class,
        () -> publisher.publish(request("req-dup", base.deviceIds())));
    assertEquals(1, count("alerts"));
    assertEquals(1, count("alert_revisions"));
    assertEquals(2, count("alert_targets"));
    assertEquals(2, count("deliveries"));
    assertEquals(1, count("alert_operations"));
    assertEquals(1, batches.size(), "failed publish must not enqueue");
  }

  @Test
  void emptyTargetListRejectedBeforeAnyWrite() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> publisher.publish(request("req-empty", List.of())));
    assertEquals(0, count("alerts"));
    assertEquals(0, count("deliveries"));
    assertTrue(batches.isEmpty());
    // Connection pool not left in a broken transactional state.
    publisher.publish(request("req-ok", base.deviceIds()));
    assertEquals(1, count("alerts"));
  }

  @Test
  void duplicateDeviceIdsAreDeduplicated() throws Exception {
    List<String> devices = List.of(base.deviceIds().get(0), base.deviceIds().get(0));
    TransactionalPublisher.Result r = publisher.publish(request("req-dedupe", devices));
    assertEquals(1, r.deliveries().size());
    assertEquals(1, count("alert_targets"));
  }

  @Test
  void concurrentPublishesBothCommit() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<TransactionalPublisher.Result> f1 =
          pool.submit(() -> publisher.publish(request("req-A", base.deviceIds())));
      Future<TransactionalPublisher.Result> f2 =
          pool.submit(() -> publisher.publish(request("req-B", base.deviceIds())));
      f1.get();
      f2.get();
    } finally {
      pool.shutdown();
    }
    assertEquals(2, count("alerts"));
    assertEquals(2, count("alert_revisions"));
    assertEquals(4, count("alert_targets"));
    assertEquals(4, count("deliveries"));
    assertEquals(4, batches.stream().mapToInt(List::size).sum());
  }
}
