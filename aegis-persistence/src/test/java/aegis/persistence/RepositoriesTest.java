package aegis.persistence;

import aegis.protocol.Priority;
import aegis.protocol.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoriesTest {
  @TempDir Path dir;
  Db db;
  Fixtures.Base base;

  @BeforeEach
  void setup() throws Exception {
    db = TestDb.fresh(dir);
    base = Fixtures.base(db, 2);
  }

  @Nested
  class Users {
    @Test
    void createAssignsUuidAndUtcIsoTimestamp() throws Exception {
      UserRepo repo = new UserRepo(db, Fixtures.CLOCK);
      User u = repo.create("alice", "argon2-hash", Role.FLOOR_MANAGER);
      assertEquals(u, repo.findById(u.id()).orElseThrow());
      assertEquals(Role.FLOOR_MANAGER, u.role());
      assertTrue(u.enabled());
      assertEquals(Fixtures.NOW, u.createdAt());
      // UUID text id + raw UTC ISO-8601 storage (fixed-width 9-digit nanos, see Sql.TS)
      UUID.fromString(u.id());
      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery("SELECT created_at FROM users WHERE id='" + u.id() + "'")) {
        assertTrue(rs.next());
        assertEquals("2026-11-21T07:00:00.000000000Z", rs.getString(1));
      }
    }

    @Test
    void findByUsernameAndMissingId() throws Exception {
      UserRepo repo = new UserRepo(db, Fixtures.CLOCK);
      User u = repo.create("bob", "hash", Role.EMPLOYEE);
      assertEquals(u, repo.findByUsername("bob").orElseThrow());
      assertEquals(Optional.empty(), repo.findById(UUID.randomUUID().toString()));
      assertEquals(Optional.empty(), repo.findByUsername("nobody"));
    }

    @Test
    void duplicateUsernameRejected() throws Exception {
      UserRepo repo = new UserRepo(db, Fixtures.CLOCK);
      repo.create("carol", "hash", Role.EMPLOYEE);
      assertThrows(SQLException.class, () -> repo.create("carol", "hash2", Role.EMPLOYEE));
    }

    @Test
    void setEnabledTogglesFlag() throws Exception {
      UserRepo repo = new UserRepo(db, Fixtures.CLOCK);
      User u = repo.create("dave", "hash", Role.EMPLOYEE);
      assertTrue(repo.setEnabled(u.id(), false));
      assertFalse(repo.findById(u.id()).orElseThrow().enabled());
    }
  }

  @Nested
  class Devices {
    @Test
    void enrollAndLookup() throws Exception {
      DeviceRepo repo = new DeviceRepo(db, Fixtures.CLOCK);
      Device d = repo.findByCode("DEV-0").orElseThrow();
      assertEquals(base.floorId(), d.floorId());
      assertEquals(Fixtures.NOW, d.enrolledAt());
      assertTrue(d.enabled());
      assertEquals(d, repo.findById(d.id()).orElseThrow());
      assertEquals(2, repo.listByFloor(base.floorId()).size());
      assertEquals(2, repo.listEnabled().size());
    }

    @Test
    void unknownFloorRejected() throws Exception {
      DeviceRepo repo = new DeviceRepo(db, Fixtures.CLOCK);
      assertThrows(SQLException.class,
          () -> repo.enroll(UUID.randomUUID().toString(), "DEV-X", "hash"));
    }

    @Test
    void disabledDevicesExcludedFromListEnabled() throws Exception {
      DeviceRepo repo = new DeviceRepo(db, Fixtures.CLOCK);
      Device d = repo.findByCode("DEV-1").orElseThrow();
      assertTrue(repo.setEnabled(d.id(), false));
      assertEquals(1, repo.listEnabled().size());
      assertEquals("DEV-0", repo.listEnabled().get(0).deviceCode());
    }
  }

  @Nested
  class RoleGrants {
    @Test
    void grantFindRevoke() throws Exception {
      RoleGrantRepo repo = new RoleGrantRepo(db, Fixtures.CLOCK);
      RoleGrant g = repo.grant(base.operatorId(), base.buildingId(), base.floorId(), "FLOOR");
      UUID.fromString(g.id());
      assertEquals(Fixtures.NOW, g.grantedAt());
      List<RoleGrant> grants = repo.findByUser(base.operatorId());
      assertEquals(List.of(g), grants);
      assertTrue(repo.revoke(g.id()));
      assertTrue(repo.findByUser(base.operatorId()).isEmpty());
    }

    @Test
    void globalScopeWithoutBuildingOrFloor() throws Exception {
      RoleGrantRepo repo = new RoleGrantRepo(db, Fixtures.CLOCK);
      RoleGrant g = repo.grant(base.operatorId(), null, null, "GLOBAL");
      assertEquals(g, repo.findByUser(base.operatorId()).get(0));
    }

    @Test
    void unknownUserRejected() throws Exception {
      RoleGrantRepo repo = new RoleGrantRepo(db, Fixtures.CLOCK);
      assertThrows(SQLException.class,
          () -> repo.grant(UUID.randomUUID().toString(), base.buildingId(), null, "BUILDING"));
    }
  }

  @Nested
  class Alerts {
    @Test
    void draftRevisionAndTargets() throws Exception {
      AlertRepo repo = new AlertRepo(db, Fixtures.CLOCK);
      Alert a = repo.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.EMERGENCY, Fixtures.NOW.plusSeconds(3600));
      assertEquals(AlertState.DRAFT, a.state());
      assertEquals(1, a.currentRevision());
      assertEquals(a, repo.findById(a.id()).orElseThrow());

      AlertRevision r = repo.addRevision(a.id(), 1, "Fire on floor 1", "Evacuate", "{}",
          base.operatorId());
      assertEquals(r, repo.findRevision(a.id(), 1).orElseThrow());
      assertEquals(List.of(r), repo.revisions(a.id()));

      AlertTarget t = repo.addTarget(a.id(), 1, base.deviceIds().get(0));
      assertEquals(List.of(t), repo.targets(a.id(), 1));

      assertTrue(repo.updateState(a.id(), AlertState.ACTIVE));
      assertEquals(AlertState.ACTIVE, repo.findById(a.id()).orElseThrow().state());
      assertEquals(List.of(a.id()),
          repo.findByState(AlertState.ACTIVE).stream().map(Alert::id).toList());
    }

    @Test
    void unknownBuildingRejected() throws Exception {
      AlertRepo repo = new AlertRepo(db, Fixtures.CLOCK);
      assertThrows(SQLException.class, () -> repo.createDraft(UUID.randomUUID().toString(),
          base.operatorId(), "FIRE", Priority.WARNING, null));
    }

    @Test
    void unknownTargetDeviceRejected() throws Exception {
      AlertRepo repo = new AlertRepo(db, Fixtures.CLOCK);
      Alert a = repo.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.WARNING, null);
      assertThrows(SQLException.class,
          () -> repo.addTarget(a.id(), 1, UUID.randomUUID().toString()));
    }
  }

  @Nested
  class Deliveries {
    private Alert alert;
    private DeliveryRepo repo;

    @BeforeEach
    void alert() throws Exception {
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      alert = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.EMERGENCY, null);
      alerts.updateState(alert.id(), AlertState.ACTIVE);
      repo = new DeliveryRepo(db, Fixtures.CLOCK);
    }

    @Test
    void createStartsPendingWithZeroAttempts() throws Exception {
      Delivery d = repo.create(alert.id(), 1, base.deviceIds().get(0));
      assertEquals(DeliveryState.PENDING, d.state());
      assertEquals(0, d.attemptCount());
      assertEquals(Fixtures.NOW, d.createdAt());
      assertEquals(d, repo.findById(d.id()).orElseThrow());
      assertEquals(List.of(d), repo.findByAlert(alert.id()));
    }

    @Test
    void retrySchedulingIncrementsAttemptCount() throws Exception {
      Delivery d = repo.create(alert.id(), 1, base.deviceIds().get(0));
      Instant retryAt = Fixtures.NOW.plusSeconds(3);
      assertTrue(repo.scheduleRetry(d.id(), retryAt));
      Delivery after = repo.findById(d.id()).orElseThrow();
      assertEquals(DeliveryState.RETRY_SCHEDULED, after.state());
      assertEquals(1, after.attemptCount());
      assertEquals(retryAt, after.nextRetryAt());
    }

    @Test
    void attemptsAreRecorded() throws Exception {
      Delivery d = repo.create(alert.id(), 1, base.deviceIds().get(0));
      DeliveryAttempt att = repo.recordAttempt(d.id(), "msg-1", "TIMEOUT");
      assertEquals(d.id(), att.deliveryId());
      assertEquals(Fixtures.NOW, att.attemptedAt());
      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery(
               "SELECT count(*) FROM delivery_attempts WHERE delivery_id='" + d.id() + "'")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
    }

    @Test
    void acksAdvanceStateMonotonically() throws Exception {
      String dev0 = base.deviceIds().get(0);
      String dev1 = base.deviceIds().get(1);
      Delivery d0 = repo.create(alert.id(), 1, dev0);
      Delivery d1 = repo.create(alert.id(), 1, dev1);

      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.RECEIVED, null);
      assertEquals(DeliveryState.RECEIVED, repo.findById(d0.id()).orElseThrow().state());
      repo.recordAck(dev0, alert.id(), 1, d0.id(), AckKind.USER, base.operatorId());
      assertEquals(DeliveryState.ACKNOWLEDGED, repo.findById(d0.id()).orElseThrow().state());

      // Late RECEIVED must not downgrade an ACKNOWLEDGED delivery.
      repo.recordAck(dev1, alert.id(), 1, d1.id(), AckKind.USER, null);
      repo.recordAck(dev1, alert.id(), 1, d1.id(), AckKind.RECEIVED, null);
      assertEquals(DeliveryState.ACKNOWLEDGED, repo.findById(d1.id()).orElseThrow().state());

      try (Connection c = db.open(); Statement s = c.createStatement();
           ResultSet rs = s.executeQuery("SELECT count(*) FROM device_acks")) {
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));
      }
    }

    @Test
    void findActiveOnlyReturnsSchedulableStates() throws Exception {
      DeliveryState[] all = DeliveryState.values();
      assertEquals(8, all.length);
      // one delivery per state needs distinct devices (UNIQUE alert,revision,device)
      DeviceRepo devices = new DeviceRepo(db, Fixtures.CLOCK);
      List<String> devs = new java.util.ArrayList<>();
      for (int i = 0; i < all.length; i++) {
        devs.add(devices.enroll(base.floorId(), "STATE-DEV-" + i, "hash").id());
      }
      AlertRepo alerts = new AlertRepo(db, Fixtures.CLOCK);
      Alert a = alerts.createDraft(base.buildingId(), base.operatorId(), "FIRE",
          Priority.CRITICAL, null);
      for (int i = 0; i < all.length; i++) {
        Delivery d = repo.create(a.id(), 1, devs.get(i));
        repo.updateState(d.id(), all[i], null, 0);
      }
      List<Delivery> active = repo.findActive();
      assertEquals(4, active.size());
      assertEquals(java.util.Set.of(DeliveryState.PENDING, DeliveryState.SENT,
              DeliveryState.RETRY_SCHEDULED, DeliveryState.FAILED),
          active.stream().map(Delivery::state).collect(java.util.stream.Collectors.toSet()));
    }
  }

  @Nested
  class Audit {
    @Test
    void appendAndQuery() throws Exception {
      AuditRepo repo = new AuditRepo(db, Fixtures.CLOCK);
      AuditEvent e1 = repo.append(base.operatorId(), "ALERT_PUBLISH", "BUILDING:" + base.buildingId(),
          "OK", "corr-1", "{\"alert\":\"x\"}");
      AuditEvent e2 = repo.append(null, "LOGIN", null, "DENIED", "corr-2", null);
      assertEquals(Fixtures.NOW, e1.occurredAt());
      UUID.fromString(e1.id());
      assertEquals(List.of(e1), repo.findByCorrelation("corr-1"));
      assertEquals(List.of(e2, e1), repo.recent(10));
      assertEquals(List.of(e2), repo.recent(1));
    }
  }
}
