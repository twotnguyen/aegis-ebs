package aegis.server;

import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AuditRepo;
import aegis.persistence.Db;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.DeliveryState;
import aegis.persistence.DeviceRepo;
import aegis.persistence.MigrationRunner;
import aegis.persistence.UserRepo;
import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the MEDIUM security gaps in docs/reviews/server-review.md:
 * S-04 sanitized INTERNAL errors, S-05 STATUS_SUBSCRIBE role/scope + WS subscriber
 * cap, S-06 ACK_USER userId verification, S-07 pre-auth TTL + connection cap,
 * S-08 fixed-width timestamps / chronological expiry, S-09 atomic update/cancel/
 * incident writes (rollback probes via SQLite RAISE triggers), S-10 recovery must
 * not exceed the retry budget (engine-level test lives in FanoutEngineTest).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerSecurityFixesTest {

  private static Path tmp;
  private static Path keystore;
  private static AegisServer server;
  private static ServerConfig config;
  private static Db db;
  private static DeliveryRepo deliveries;
  private static AlertRepo alerts;
  private static AuditRepo auditRepo;
  private static UserRepo users;
  private static DeviceRepo devices;
  private static String adminId;
  private static TestTlsClient operator;

  @BeforeAll
  static void startServer() throws Exception {
    tmp = Files.createTempDirectory("aegis-sec");
    keystore = TestSupport.generateKeystore(tmp);
    config = ServerConfig.builder(tmp.resolve("sec.db"), keystore, "changeit")
        .tlsPort(0).wsPort(0)
        .devicesPerZone(2)
        .heartbeat(800, 3)
        .retry(200, 3)
        .statusBatchMs(100)
        .argon2(1, 4096, 1)
        .preAuthTimeoutMs(1500)
        .maxConnections(12)
        .build();
    server = new AegisServer(config);
    server.start();
    openRepos();
    adminId = users.findByUsername("admin").orElseThrow().id();
    operator = authOperator("admin", "admin-demo-secret");
  }

  private static void openRepos() {
    db = new Db(config.dbFile());
    deliveries = new DeliveryRepo(db);
    alerts = new AlertRepo(db);
    auditRepo = new AuditRepo(db);
    users = new UserRepo(db);
    devices = new DeviceRepo(db);
  }

  @AfterAll
  static void stopServer() throws Exception {
    if (operator != null) operator.close();
    if (server != null) server.close();
    if (tmp != null) {
      try (Stream<Path> paths = Files.walk(tmp)) {
        paths.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (java.io.IOException ignored) {
            // SQLite WAL sidecars (-shm/-wal) can vanish mid-cleanup
          }
        });
      }
    }
  }

  // ---- helpers -------------------------------------------------------------

  private static TestTlsClient authOperator(String username, String password) throws Exception {
    TestTlsClient client = new TestTlsClient(server.tlsPort(), true, true);
    Envelope hello = client.request("HELLO", Json.obj().put("clientType", "OPERATOR"), 5000);
    assertEquals("SERVER_HELLO", hello.type());
    Envelope auth = client.request("AUTH_OPERATOR",
        Json.obj().put("username", username).put("password", password), 10000);
    assertTrue(auth.payload().get("ok").asBoolean(), "auth must succeed: " + auth.payload());
    return client;
  }

  private static TestTlsClient authDevice(String code) throws Exception {
    TestTlsClient client = new TestTlsClient(server.tlsPort(), true, true);
    Envelope auth = client.request("AUTH_DEVICE",
        Json.obj().put("deviceCode", code).put("credential", "device-demo-secret"), 10000);
    assertTrue(auth.payload().get("ok").asBoolean(), "device auth must succeed: " + auth.payload());
    return client;
  }

  private static Envelope publish(TestTlsClient client, String requestId, int floor, String zone,
                                  String priority, String title) {
    ObjectNode payload = Json.obj();
    payload.put("requestId", requestId);
    payload.put("floorNumber", floor);
    payload.put("zoneCode", zone);
    payload.put("category", "FIRE");
    payload.put("priority", priority);
    payload.put("title", title);
    payload.put("instruction", "SIMULATION — training only.");
    payload.put("simulation", true);
    return client.request("ALERT_PUBLISH", payload, 15000);
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = db.open(); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static int countRows(String table) throws SQLException {
    try (Connection c = db.open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }

  // ---- 1. S-05: STATUS_SUBSCRIBE role/scope ----------------------------------

  @Test
  @Order(1)
  void statusSubscribeEnforcesRoleAndScope() throws Exception {
    Envelope a1 = publish(operator, "req-sec-f1", 1, "A", "WARNING", "floor1 alert");
    assertTrue(a1.payload().get("ok").asBoolean(), a1.payload().toString());
    Envelope a7 = publish(operator, "req-sec-f7", 7, "A", "WARNING", "floor7 alert");
    assertTrue(a7.payload().get("ok").asBoolean(), a7.payload().toString());
    String f1Alert = a1.payload().get("alertId").asText();
    String f7Alert = a7.payload().get("alertId").asText();

    // EMPLOYEE: no read-plane access at all (SECURITY.md §4.2).
    // Denial replies COMMAND_RESULT (not STATUS_SNAPSHOT), so send + await.
    TestTlsClient employee = authOperator("employee", "employee-demo-secret");
    employee.send("STATUS_SUBSCRIBE", Json.obj(), null);
    Envelope denied = employee.await("COMMAND_RESULT", 8000);
    assertFalse(denied.payload().get("ok").asBoolean());
    assertEquals("FORBIDDEN", denied.payload().get("code").asText());
    employee.close();

    // FLOOR_MANAGER: only the granted floor, alerts filtered to scope
    TestTlsClient floor7 = authOperator("floor7", "floor7-demo-secret");
    Envelope scoped = floor7.request("STATUS_SUBSCRIBE", Json.obj(), 8000);
    assertEquals("STATUS_SNAPSHOT", scoped.type());
    TestSupport.until("scoped snapshot carries the floor7 alert", () -> {
      Envelope s = floor7.request("STATUS_SUBSCRIBE", Json.obj(), 8000);
      JsonNode floors = s.payload().get("floors");
      if (floors.size() != 1 || floors.get(0).get("floorNumber").asInt() != 7) return false;
      for (JsonNode a : s.payload().get("activeAlerts")) {
        if (a.get("alertId").asText().equals(f1Alert)) return false; // out of scope leaked
        if (a.get("alertId").asText().equals(f7Alert)) return true;
      }
      return false;
    }, 5000);
    floor7.close();

    // SECURITY_OPERATOR (building grant) and SYSTEM_ADMIN: full building view
    TestTlsClient secops = authOperator("secops", "secops-demo-secret");
    Envelope building = secops.request("STATUS_SUBSCRIBE", Json.obj(), 8000);
    assertEquals("STATUS_SNAPSHOT", building.type());
    assertEquals(10, building.payload().get("floors").size());
    secops.close();
    Envelope full = operator.request("STATUS_SUBSCRIBE", Json.obj(), 8000);
    assertEquals(10, full.payload().get("floors").size());

    for (String id : List.of(f1Alert, f7Alert)) {
      Envelope cancel = operator.request("ALERT_CANCEL", Json.obj()
          .put("requestId", "req-sec-clean-" + id).put("alertId", id).put("reason", "cleanup"), 10000);
      assertTrue(cancel.payload().get("ok").asBoolean());
    }
  }

  // ---- 2. S-06 + S-01: ACK_USER accountability + duplicate flag ---------------

  @Test
  @Order(2)
  void ackUserRequiresVerifiedUserIdAndFlagsDuplicates() throws Exception {
    TestTlsClient d1 = authDevice("DEV-A-F01-A-01");
    String d1Id = devices.findByCode("DEV-A-F01-A-01").orElseThrow().id();
    Envelope pub = publish(operator, "req-sec-ack", 1, "A", "CRITICAL", "ack drill");
    assertTrue(pub.payload().get("ok").asBoolean(), pub.payload().toString());
    String alertId = pub.payload().get("alertId").asText();
    // only accept the push for THIS alert (resume may deliver older alerts first)
    Envelope push = d1.await("ALERT_PUSH", 8000);
    long pushDeadline = System.currentTimeMillis() + 8000;
    while (!push.payload().get("alertId").asText().equals(alertId)) {
      assertTrue(System.currentTimeMillis() < pushDeadline, "no push for the published alert");
      push = d1.await("ALERT_PUSH", 8000);
    }
    String deliveryId = push.payload().get("deliveryId").asText();

    Envelope ackR = d1.request("ACK_RECEIVED", Json.obj()
        .put("alertId", alertId).put("deliveryId", deliveryId)
        .put("revision", 1).put("deviceId", d1Id), 8000);
    assertTrue(ackR.payload().get("ok").asBoolean());
    assertFalse(ackR.payload().get("duplicate").asBoolean());

    // spoofed userId: rejected, nothing recorded
    d1.send("ACK_USER", Json.obj()
        .put("alertId", alertId).put("deliveryId", deliveryId)
        .put("revision", 1).put("deviceId", d1Id).put("userId", "not-a-real-user"), null);
    Envelope spoof = d1.await("ERROR", 8000);
    assertEquals("FORBIDDEN", spoof.payload().get("code").asText());
    assertEquals(DeliveryState.RECEIVED, deliveries.findById(deliveryId).orElseThrow().state());

    // verified userId: accepted and attributed
    Envelope ackU = d1.request("ACK_USER", Json.obj()
        .put("alertId", alertId).put("deliveryId", deliveryId)
        .put("revision", 1).put("deviceId", d1Id).put("userId", adminId), 8000);
    assertTrue(ackU.payload().get("ok").asBoolean());
    assertEquals("ACKNOWLEDGED", ackU.payload().get("state").asText());
    assertFalse(ackU.payload().get("duplicate").asBoolean());

    // idempotent replay: duplicate=true + DUPLICATE audit (S-01)
    Envelope dup = d1.request("ACK_USER", Json.obj()
        .put("alertId", alertId).put("deliveryId", deliveryId)
        .put("revision", 1).put("deviceId", d1Id).put("userId", adminId), 8000);
    assertTrue(dup.payload().get("ok").asBoolean());
    assertTrue(dup.payload().get("duplicate").asBoolean(), "replayed ack must flag duplicate");
    assertTrue(auditRepo.recent(2000).stream()
        .anyMatch(e -> e.action().equals("ACK_USER") && e.outcome().equals("DUPLICATE")),
        "duplicate ack must be audited as DUPLICATE");
    d1.close();
  }

  // ---- 3. S-04: INTERNAL errors must not leak exception details ----------------

  @Test
  @Order(3)
  void internalErrorsDoNotLeakExceptionDetails() throws Exception {
    Envelope pub = publish(operator, "req-sec-sanitize", 1, "A", "WARNING", "sanitize probe");
    assertTrue(pub.payload().get("ok").asBoolean());
    String alertId = pub.payload().get("alertId").asText();
    // break the revision row so ALERT_UPDATE hits an internal RuntimeException
    exec("DELETE FROM alert_revisions WHERE alert_id='" + alertId + "'");
    // router-level internal faults reply ERROR (protocol/auth-gate channel)
    operator.send("ALERT_UPDATE", Json.obj()
        .put("requestId", "req-sec-sanitize-upd").put("alertId", alertId).put("title", "x"), null);
    Envelope upd = operator.await("ERROR", 10000);
    assertEquals("INTERNAL", upd.payload().get("code").asText());
    String msg = upd.payload().get("message").asText();
    assertEquals("internal server error", msg, "wire message must be generic (NFR-017)");
    assertFalse(msg.contains("NoSuchElement"), "exception class leaked");
    assertFalse(msg.contains("revision"), "storage detail leaked");
  }

  // ---- 4. S-09: ALERT_UPDATE is one transaction --------------------------------

  @Test
  @Order(4)
  void alertUpdateRollsBackAsOneTransaction() throws Exception {
    Envelope pub = publish(operator, "req-sec-upd", 1, "A", "WARNING", "atomic update");
    assertTrue(pub.payload().get("ok").asBoolean());
    String alertId = pub.payload().get("alertId").asText();
    TestSupport.until("rev1 deliveries exist", () -> {
      try {
        return deliveries.findByAlert(alertId).size() == 2;
      } catch (SQLException e) {
        return false;
      }
    }, 5000);
    // simulate a crash at the LAST write of the update (operation insert)
    exec("CREATE TRIGGER sec_fail_op BEFORE INSERT ON alert_operations "
        + "BEGIN SELECT RAISE(ABORT, 'simulated crash'); END");
    try {
      Envelope upd = operator.request("ALERT_UPDATE", Json.obj()
          .put("requestId", "req-sec-upd-1").put("alertId", alertId).put("title", "rev2"), 10000);
      assertFalse(upd.payload().get("ok").asBoolean(), "failing operation insert must fail the update");
      assertEquals(1, alerts.findById(alertId).orElseThrow().currentRevision(),
          "rollback must undo the revision bump");
      assertTrue(alerts.findRevision(alertId, 2).isEmpty(), "no orphan revision row");
      for (Delivery d : deliveries.findByAlert(alertId)) {
        assertEquals(1, d.revision(), "no rev2 deliveries without a committed operation");
        assertNotEquals(DeliveryState.CANCELLED, d.state(), "rev1 deliveries must survive the rollback");
      }
    } finally {
      exec("DROP TRIGGER sec_fail_op");
    }
    Envelope ok = operator.request("ALERT_UPDATE", Json.obj()
        .put("requestId", "req-sec-upd-1").put("alertId", alertId).put("title", "rev2"), 10000);
    assertTrue(ok.payload().get("ok").asBoolean(), ok.payload().toString());
    assertEquals(2, ok.payload().get("revision").asInt());
    assertEquals(2, alerts.findById(alertId).orElseThrow().currentRevision());
    assertEquals(2, deliveries.findByAlert(alertId).stream().filter(d -> d.revision() == 2).count());
  }

  // ---- 5. S-09: ALERT_CANCEL is one transaction --------------------------------

  @Test
  @Order(5)
  void alertCancelRollsBackAsOneTransaction() throws Exception {
    Envelope pub = publish(operator, "req-sec-cancel", 1, "A", "WARNING", "atomic cancel");
    assertTrue(pub.payload().get("ok").asBoolean());
    String alertId = pub.payload().get("alertId").asText();
    exec("CREATE TRIGGER sec_fail_op BEFORE INSERT ON alert_operations "
        + "BEGIN SELECT RAISE(ABORT, 'simulated crash'); END");
    try {
      Envelope cancel = operator.request("ALERT_CANCEL", Json.obj()
          .put("requestId", "req-sec-cancel-1").put("alertId", alertId).put("reason", "probe"), 10000);
      assertFalse(cancel.payload().get("ok").asBoolean());
      assertEquals(aegis.persistence.AlertState.ACTIVE, alerts.findById(alertId).orElseThrow().state(),
          "rollback must leave the alert ACTIVE");
      for (Delivery d : deliveries.findByAlert(alertId)) {
        assertNotEquals(DeliveryState.CANCELLED, d.state(), "deliveries must survive the rollback");
      }
    } finally {
      exec("DROP TRIGGER sec_fail_op");
    }
    Envelope ok = operator.request("ALERT_CANCEL", Json.obj()
        .put("requestId", "req-sec-cancel-1").put("alertId", alertId).put("reason", "probe"), 10000);
    assertTrue(ok.payload().get("ok").asBoolean(), ok.payload().toString());
    assertEquals(aegis.persistence.AlertState.CANCELLED, alerts.findById(alertId).orElseThrow().state());
  }

  // ---- 6. S-09: incident writes are one transaction -----------------------------

  @Test
  @Order(6)
  void incidentWritesRollBackAsOneTransaction() throws Exception {
    exec("CREATE TRIGGER sec_fail_ev BEFORE INSERT ON incident_events "
        + "BEGIN SELECT RAISE(ABORT, 'simulated crash'); END");
    try {
      Envelope created = operator.request("INCIDENT_CREATE", Json.obj()
          .put("kind", "FIRE").put("summary", "atomic probe").put("simulation", true), 10000);
      assertFalse(created.payload().get("ok").asBoolean());
      assertEquals(0, countRows("incidents"), "failed create must not leave an orphan incident");
    } finally {
      exec("DROP TRIGGER sec_fail_ev");
    }
    Envelope ok = operator.request("INCIDENT_CREATE", Json.obj()
        .put("kind", "FIRE").put("summary", "atomic probe").put("simulation", true), 10000);
    assertTrue(ok.payload().get("ok").asBoolean(), ok.payload().toString());
    String incidentId = ok.payload().get("incidentId").asText();
    assertEquals(1, countRows("incidents"));

    exec("CREATE TRIGGER sec_fail_ev BEFORE INSERT ON incident_events "
        + "BEGIN SELECT RAISE(ABORT, 'simulated crash'); END");
    try {
      Envelope tr = operator.request("INCIDENT_TRANSITION", Json.obj()
          .put("incidentId", incidentId).put("state", "INVESTIGATING"), 10000);
      assertFalse(tr.payload().get("ok").asBoolean());
      assertEquals("OPEN", server.store().findIncident(incidentId).orElseThrow().state(),
          "failed transition must not change the incident state");
    } finally {
      exec("DROP TRIGGER sec_fail_ev");
    }
    Envelope tr2 = operator.request("INCIDENT_TRANSITION", Json.obj()
        .put("incidentId", incidentId).put("state", "INVESTIGATING"), 10000);
    assertTrue(tr2.payload().get("ok").asBoolean(), tr2.payload().toString());
    assertEquals("INVESTIGATING", server.store().findIncident(incidentId).orElseThrow().state());
  }

  // ---- 7. S-07: pre-auth TTL (idle AND keepalive-lingering) ----------------------

  @Test
  @Order(7)
  void preAuthConnectionsExpireButAuthenticatedOnesSurvive() throws Exception {
    // idle pre-auth: HELLO then silence -> the read timeout must close it
    TestTlsClient idle = new TestTlsClient(server.tlsPort(), true, false);
    idle.request("HELLO", Json.obj().put("clientType", "OPERATOR"), 5000);
    idle.awaitClosed(5000);

    // active pre-auth: keepalive frames must NOT extend the absolute TTL
    TestTlsClient linger = new TestTlsClient(server.tlsPort(), true, false);
    linger.request("HELLO", Json.obj().put("clientType", "OPERATOR"), 5000);
    long end = System.currentTimeMillis() + 4500;
    while (!linger.isClosed() && System.currentTimeMillis() < end) {
      linger.send("HEARTBEAT", Json.obj(), null);
      TestSupport.sleep(200);
    }
    assertTrue(linger.isClosed(), "pre-auth session must expire despite keepalives");
    linger.close();

    // authenticated device survives well past the pre-auth TTL
    TestTlsClient dev = authDevice("DEV-A-F02-A-01");
    TestSupport.sleep(2500);
    assertFalse(dev.isClosed(), "authenticated session must not hit the pre-auth TTL");
    dev.close();
  }

  // ---- 8. S-07: connection cap ---------------------------------------------------

  @Test
  @Order(8)
  void connectionCapRejectsExcessSockets() throws Exception {
    List<TestTlsClient> raw = new ArrayList<>();
    int rejected = 0;
    try {
      for (int i = 0; i < 15; i++) {
        try {
          raw.add(new TestTlsClient(server.tlsPort(), false, false));
        } catch (Exception e) {
          rejected++; // server closed the socket during/just after accept
        }
      }
      TestSupport.sleep(800); // cap rejects are immediate; pre-auth TTL is 1500ms
      for (TestTlsClient c : raw) {
        if (peerGone(c)) rejected++;
      }
      assertTrue(rejected >= 3, "excess connections must be rejected at the cap (rejected=" + rejected + ")");
      assertTrue(server.sessions().all().size() <= config.maxConnections(),
          "registry must never exceed the connection cap");
    } finally {
      for (TestTlsClient c : raw) c.close();
    }
  }

  /** EOF/reset => server closed; read timeout => still open and silent. */
  private static boolean peerGone(TestTlsClient c) {
    try {
      c.socket().setSoTimeout(300);
      return c.socket().getInputStream().read() < 0;
    } catch (java.net.SocketTimeoutException e) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  // ---- 9. S-05: WS feed subscriber cap -------------------------------------------

  @Test
  @Order(9)
  void wsFeedCapsSubscribers() throws Exception {
    List<TestWsClient> clients = new ArrayList<>();
    try {
      for (int i = 0; i < 64; i++) {
        TestWsClient ws = new TestWsClient(server.wsPort());
        ws.await("STATUS_SNAPSHOT", 8000);
        clients.add(ws);
      }
      boolean rejected;
      try {
        TestWsClient extra = new TestWsClient(server.wsPort());
        extra.close();
        rejected = false; // handshake succeeded: no cap enforced
      } catch (AssertionError | IOException e) {
        rejected = true; // 503 / closed before upgrade
      }
      assertTrue(rejected, "subscriber beyond the cap must be rejected");
      assertEquals(64, server.statusFeed().subscriberCount());
    } finally {
      for (TestWsClient ws : clients) ws.close();
    }
  }

  // ---- 10. S-08: fixed-width timestamps + chronological expiry --------------------

  @Test
  @Order(10)
  void timestampsAreFixedWidthAndExpiryIsChronological() throws Exception {
    assertEquals("2026-01-01T00:00:00.400000000Z", Json.ts(Instant.parse("2026-01-01T00:00:00.4Z")));
    assertEquals("2026-01-01T00:00:00.000000000Z", Json.ts(Instant.parse("2026-01-01T00:00:00Z")));
    assertTrue(Json.ts(Instant.parse("2026-01-01T00:00:00.4Z"))
        .compareTo(Json.ts(Instant.parse("2026-01-01T00:00:00.45Z"))) < 0,
        "TEXT order must match chronology at mixed precision");

    // expiry at mixed precision: now = whole second, expires_at = +500ms.
    // Variable-width ISO_INSTANT sorts 'Z' after '.', wrongly expiring the alert.
    Db xdb = new Db(tmp.resolve("expiry.db"));
    new MigrationRunner(xdb).migrate();
    String buildingId = "B-EXPIRY";
    String opId = UUID.randomUUID().toString();
    try (Connection c = xdb.open(); Statement s = c.createStatement()) {
      s.executeUpdate("INSERT INTO buildings(id,code,name) VALUES('" + buildingId + "','X','Expiry')");
      s.executeUpdate("INSERT INTO users(id,username,password_hash,role,enabled,created_at) VALUES('"
          + opId + "','expiry-op','x','SYSTEM_ADMIN',1,'2026-01-01T00:00:00.000000000Z')");
    }
    Clock atZero = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    AlertRepo xalerts = new AlertRepo(xdb, atZero);
    DeliveryRepo xdeliveries = new DeliveryRepo(xdb, atZero);
    Alert alert = xalerts.createDraft(buildingId, opId, "FIRE", Priority.WARNING,
        Instant.parse("2026-01-01T00:00:00.5Z"));
    xalerts.updateState(alert.id(), aegis.persistence.AlertState.ACTIVE);
    ServerStore zeroStore = new ServerStore(xdb, atZero, xalerts, xdeliveries);
    assertTrue(zeroStore.expireOverdue().isEmpty(), "alert expiring at .5Z is NOT overdue at .0Z");
    assertTrue(zeroStore.activeAlerts(buildingId).stream().anyMatch(a -> a.id().equals(alert.id())),
        "not-yet-expired alert must stay active");
    Clock atSix = Clock.fixed(Instant.parse("2026-01-01T00:00:00.6Z"), ZoneOffset.UTC);
    ServerStore sixStore = new ServerStore(xdb, atSix, new AlertRepo(xdb, atSix), new DeliveryRepo(xdb, atSix));
    assertEquals(List.of(alert.id()), sixStore.expireOverdue(), "overdue alert must expire");
  }
}
