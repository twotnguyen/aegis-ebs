package aegis.persistence;

import aegis.protocol.Priority;
import aegis.protocol.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the peer-review issues (docs/reviews/persistence-review.md):
 * M1 cross-device ack, L1 busy_timeout ordering, L2 mixed-precision timestamp
 * ordering, L3 duplicate-ack idempotency, L4 canonical payload_hash.
 */
class PeerReviewFixesTest {
  @TempDir Path dir;
  Db db;
  Fixtures.Base base;

  @BeforeEach
  void setup() throws Exception {
    db = TestDb.fresh(dir);
    base = Fixtures.base(db, 2);
  }

  private int count(String table) throws SQLException {
    try (Connection c = db.open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

  /** M1: an ack must only be accepted for the delivery's own device/alert/revision. */
  @Nested
  class CrossDeviceAckRejected {
    DeliveryRepo repo;
    Alert alert;
    String dev0;
    String dev1;
    Delivery d0;

    @BeforeEach
    void delivery() throws Exception {
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      alert = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.EMERGENCY, null);
      alerts.updateState(alert.id(), AlertState.ACTIVE);
      repo = new DeliveryRepo(db, Fixtures.CLOCK);
      dev0 = base.deviceIds().get(0);
      dev1 = base.deviceIds().get(1);
      d0 = repo.create(alert.id(), 1, dev0);
    }

    private void assertDeliveryUntouched() throws SQLException {
      assertEquals(DeliveryState.PENDING, repo.findById(d0.id()).orElseThrow().state(),
          "rejected ack must not change the delivery state");
      assertEquals(0, count("device_acks"), "rejected ack must not leave an ack row");
    }

    @Test
    void ackFromAnotherDeviceIsRejected() throws Exception {
      assertThrows(SQLException.class,
          () -> repo.recordAck(dev1, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId()));
      assertDeliveryUntouched();
    }

    @Test
    void ackWithWrongAlertIsRejected() throws Exception {
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      Alert other = alerts.createDraft(base.buildingId(), base.operatorId(), "QUAKE",
          Priority.WARNING, null);
      assertThrows(SQLException.class,
          () -> repo.recordAck(dev0, other.id(), 1, d0.id(), AckKind.USER, base.operatorId()));
      assertDeliveryUntouched();
    }

    @Test
    void ackWithWrongRevisionIsRejected() throws Exception {
      assertThrows(SQLException.class,
          () -> repo.recordAck(dev0, alert.id(), 2, d0.id(), AckKind.USER, base.operatorId()));
      assertDeliveryUntouched();
    }

    @Test
    void ackForUnknownDeliveryIsRejected() throws Exception {
      assertThrows(SQLException.class,
          () -> repo.recordAck(dev0, alert.id(), 1, UUID.randomUUID().toString(),
              AckKind.USER, base.operatorId()));
      assertDeliveryUntouched();
    }
  }

  /** L3: at-least-once transport retries the same ack; persistence must be idempotent. */
  @Nested
  class DuplicateAckIsIdempotent {
    DeliveryRepo repo;
    Alert alert;
    String dev0;
    Delivery d0;

    @BeforeEach
    void delivery() throws Exception {
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      alert = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.EMERGENCY, null);
      alerts.updateState(alert.id(), AlertState.ACTIVE);
      repo = new DeliveryRepo(db, Fixtures.CLOCK);
      dev0 = base.deviceIds().get(0);
      d0 = repo.create(alert.id(), 1, dev0);
    }

    @Test
    void repeatingSameKindIsANoOp() throws Exception {
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null);
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null); // retry: must not throw
      assertEquals(DeliveryState.RECEIVED, repo.findById(d0.id()).orElseThrow().state());
      assertEquals(1, count("device_acks"), "duplicate ack must not insert a second row");

      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId());
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId());
      assertEquals(DeliveryState.ACKNOWLEDGED, repo.findById(d0.id()).orElseThrow().state());
      assertEquals(2, count("device_acks"));
    }

    @Test
    void duplicatesNeverDowngradeOrResurrectTerminalState() throws Exception {
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId());
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null); // late lower kind
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null); // its duplicate
      assertEquals(DeliveryState.ACKNOWLEDGED, repo.findById(d0.id()).orElseThrow().state());
      assertEquals(2, count("device_acks"));

      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.CANCEL, base.operatorId());
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.CANCEL, base.operatorId());
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId());
      assertEquals(DeliveryState.CANCELLED, repo.findById(d0.id()).orElseThrow().state(),
          "CANCEL is terminal; later duplicates must not resurrect the delivery");
      assertEquals(3, count("device_acks"));
    }
  }

  /** L2: TEXT ORDER BY must match chronology even when precisions are mixed. */
  @Nested
  class MixedPrecisionTimestampOrdering {
    @Test
    void auditRecentSortsChronologicallyNotLexicographically() throws Exception {
      Instant t0 = Fixtures.NOW;                      // whole second
      Instant t1 = t0.plusNanos(123_456_789);         // 9-digit fraction
      Instant t2 = t0.plusMillis(500);                // 3-digit fraction
      Instant t3 = t0.plusSeconds(1);                 // whole second again
      // Insert out of chronological order.
      AuditEvent e3 = new AuditRepo(db, Clock.fixed(t3, ZoneOffset.UTC))
          .append(null, "E3", null, "OK", "corr-order", null);
      AuditEvent e0 = new AuditRepo(db, Clock.fixed(t0, ZoneOffset.UTC))
          .append(null, "E0", null, "OK", "corr-order", null);
      AuditEvent e2 = new AuditRepo(db, Clock.fixed(t2, ZoneOffset.UTC))
          .append(null, "E2", null, "OK", "corr-order", null);
      AuditEvent e1 = new AuditRepo(db, Clock.fixed(t1, ZoneOffset.UTC))
          .append(null, "E1", null, "OK", "corr-order", null);

      AuditRepo reader = new AuditRepo(db, Fixtures.CLOCK);
      assertEquals(List.of(e3, e2, e1, e0), reader.recent(10),
          "recent() must be reverse-chronological across mixed precisions");
      assertEquals(List.of(e0, e1, e2, e3), reader.findByCorrelation("corr-order"),
          "findByCorrelation() must be chronological across mixed precisions");
    }

    @Test
    void findActiveSortsOldestFirstAcrossPrecisions() throws Exception {
      DeviceRepo devices = new DeviceRepo(db, Fixtures.CLOCK);
      List<String> devs = new ArrayList<>(base.deviceIds());
      devs.add(devices.enroll(base.floorId(), "ORD-DEV-2", "hash").id());
      devs.add(devices.enroll(base.floorId(), "ORD-DEV-3", "hash").id());
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      Alert a = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.CRITICAL, null);
      Instant t0 = Fixtures.NOW;
      Instant[] times = {t0.plusMillis(500), t0, t0.plusSeconds(1), t0.plusNanos(123_456_789)};
      List<Delivery> created = new ArrayList<>();
      for (int i = 0; i < times.length; i++) {
        created.add(new DeliveryRepo(db, Clock.fixed(times[i], ZoneOffset.UTC))
            .create(a.id(), 1, devs.get(i)));
      }
      List<Delivery> active = new DeliveryRepo(db, Fixtures.CLOCK).findActive();
      assertEquals(List.of(created.get(1), created.get(3), created.get(0), created.get(2)), active,
          "recovery order must be oldest-first by true instant, not by text bytes");
    }

    @Test
    void storedTimestampsAreFixedWidthUtcAndLossless() throws Exception {
      Instant precise = Fixtures.NOW.plusNanos(123_456_789);
      UserRepo users = new UserRepo(db, Clock.fixed(precise, ZoneOffset.UTC));
      User u = users.create("precision", "hash", Role.EMPLOYEE);
      assertEquals(precise, users.findById(u.id()).orElseThrow().createdAt(),
          "nanosecond precision must round-trip exactly");
      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery("SELECT created_at FROM users WHERE id='" + u.id() + "'")) {
        assertTrue(rs.next());
        assertEquals("2026-11-21T07:00:00.123456789Z", rs.getString(1));
      }
      User whole = new UserRepo(db, Fixtures.CLOCK).create("whole", "hash", Role.EMPLOYEE);
      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery(
               "SELECT created_at FROM users WHERE id='" + whole.id() + "'")) {
        assertTrue(rs.next());
        assertEquals("2026-11-21T07:00:00.000000000Z", rs.getString(1),
            "whole seconds must carry the same fixed-width fraction as every other row");
      }
    }

    @Test
    void legacyVariableWidthTimestampsStillParse() throws Exception {
      // Rows written by the OLD variable-width formatter must remain readable.
      try (Connection c = db.open(); Statement s = c.createStatement()) {
        s.executeUpdate("INSERT INTO audit_events(id,occurred_at,action,outcome) VALUES('"
            + UUID.randomUUID() + "','2026-11-21T06:59:58Z','LEGACY_WHOLE','OK')");
        s.executeUpdate("INSERT INTO audit_events(id,occurred_at,action,outcome) VALUES('"
            + UUID.randomUUID() + "','2026-11-21T06:59:59.5Z','LEGACY_HALF','OK')");
      }
      List<AuditEvent> recent = new AuditRepo(db, Fixtures.CLOCK).recent(10);
      assertEquals(2, recent.size());
      assertEquals(Instant.parse("2026-11-21T06:59:59.500Z"), recent.get(0).occurredAt());
      assertEquals("LEGACY_HALF", recent.get(0).action());
      assertEquals(Instant.parse("2026-11-21T06:59:58Z"), recent.get(1).occurredAt());
      assertEquals("LEGACY_WHOLE", recent.get(1).action());
    }
  }

  /** L1: busy_timeout must be armed BEFORE the WAL switch so first-open races wait. */
  @Nested
  class ConcurrentFirstOpen {
    @Test
    void parallelFirstOpenAndMigrateNeverHitsSqliteBusy() throws Exception {
      int threads = 8;
      int trials = 6;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        for (int trial = 0; trial < trials; trial++) {
          Path file = dir.resolve("race-" + trial + "-" + UUID.randomUUID() + ".db");
          CyclicBarrier barrier = new CyclicBarrier(threads);
          List<Future<?>> futures = new ArrayList<>();
          for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
              barrier.await();
              new MigrationRunner(new Db(file)).migrate();
              return null;
            }));
          }
          List<Throwable> errors = new ArrayList<>();
          for (Future<?> f : futures) {
            try {
              f.get();
            } catch (ExecutionException e) {
              errors.add(e.getCause());
            }
          }
          assertTrue(errors.isEmpty(),
              "trial " + trial + ": concurrent first-open must not raise SQLITE_BUSY: " + errors);
          try (Connection c = new Db(file).open(); Statement s = c.createStatement();
               ResultSet rs = s.executeQuery(
                   "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            assertTrue(rs.next());
            assertEquals(26, rs.getInt(1), "schema must converge to exactly 26 tables");
          }
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  /** L4: payload_hash must be a faithful, unambiguous fingerprint of the whole payload. */
  @Nested
  class PayloadHashCanonical {
    TransactionalPublisher publisher;

    @BeforeEach
    void publisher() {
      publisher = new TransactionalPublisher(db, Fixtures.CLOCK, batch -> {});
    }

    private TransactionalPublisher.Request req(String requestId, String title, String body) {
      return new TransactionalPublisher.Request(requestId, base.buildingId(), base.operatorId(),
          "FIRE", Priority.EMERGENCY, title, body, "{}",
          Fixtures.NOW.plusSeconds(3600), base.deviceIds());
    }

    private String hashOf(String requestId) throws SQLException {
      try (Connection c = db.open();
           PreparedStatement ps = c.prepareStatement(
               "SELECT payload_hash FROM alert_operations WHERE request_id=?")) {
        ps.setString(1, requestId);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "missing alert_operations row for " + requestId);
          String hash = rs.getString(1);
          assertEquals(64, hash.length(), "sha256 hex expected");
          return hash;
        }
      }
    }

    @Test
    void embeddedNewlinesCannotShiftFieldBoundaries() throws Exception {
      publisher.publish(req("req-nl-a", "a\nb", "c"));
      publisher.publish(req("req-nl-b", "a", "b\nc"));
      assertNotEquals(hashOf("req-nl-a"), hashOf("req-nl-b"),
          "title/body newline placement must change the fingerprint");
    }

    @Test
    void hashCoversEveryPayloadField() throws Exception {
      publisher.publish(req("req-base", "Fire on floor 1", "Evacuate now"));
      String baseline = hashOf("req-base");

      String building2 = UUID.randomUUID().toString();
      try (Connection c = db.open(); Statement s = c.createStatement()) {
        s.executeUpdate("INSERT INTO buildings(id,code,name) VALUES('" + building2
            + "','B2-" + building2.substring(0, 8) + "','Annex')");
      }
      String user2 = new UserRepo(db, Fixtures.CLOCK)
          .create("operator2", "hash", Role.SECURITY_OPERATOR).id();
      Instant expires = Fixtures.NOW.plusSeconds(3600);
      List<String> devs = base.deviceIds();

      List<TransactionalPublisher.Request> variants = List.of(
          new TransactionalPublisher.Request("req-v-title", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Other title", "Evacuate now", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-body", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Other body", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-meta", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{\"k\":1}", expires, devs),
          new TransactionalPublisher.Request("req-v-category", base.buildingId(), base.operatorId(),
              "QUAKE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-priority", base.buildingId(), base.operatorId(),
              "FIRE", Priority.INFO, "Fire on floor 1", "Evacuate now", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-createdBy", base.buildingId(), user2,
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-building", building2, base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}", expires, devs),
          new TransactionalPublisher.Request("req-v-expires", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}",
              Fixtures.NOW.plusSeconds(7200), devs),
          new TransactionalPublisher.Request("req-v-noexpiry", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}", null, devs),
          new TransactionalPublisher.Request("req-v-devices", base.buildingId(), base.operatorId(),
              "FIRE", Priority.EMERGENCY, "Fire on floor 1", "Evacuate now", "{}", expires,
              List.of(devs.get(0))));
      for (TransactionalPublisher.Request v : variants) {
        publisher.publish(v);
        assertNotEquals(baseline, hashOf(v.requestId()),
            "payload_hash must cover the field changed by " + v.requestId());
      }
    }

    @Test
    void samePayloadWithDifferentRequestIdHasStableHash() throws Exception {
      publisher.publish(req("req-stable-1", "Same title", "Same body"));
      publisher.publish(req("req-stable-2", "Same title", "Same body"));
      assertEquals(hashOf("req-stable-1"), hashOf("req-stable-2"),
          "the fingerprint covers the payload, not the idempotency key");
    }
  }
}
