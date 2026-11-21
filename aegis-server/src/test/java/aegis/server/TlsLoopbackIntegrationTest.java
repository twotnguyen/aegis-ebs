package aegis.server;

import aegis.persistence.AlertRepo;
import aegis.persistence.AlertState;
import aegis.persistence.AuditRepo;
import aegis.persistence.Db;
import aegis.persistence.DeviceRepo;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.DeliveryState;
import aegis.persistence.UserRepo;
import aegis.protocol.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end vertical slice over REAL loopback TLS (self-signed keytool cert):
 * publish -> fanout -> ACK_RECEIVED/ACK_USER -> retry -> FAILED -> cancel
 * tombstone -> reconnect SYNC -> WebSocket status/1 contract -> heartbeat
 * suspect -> slow-client policy -> crash recovery across a server restart.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TlsLoopbackIntegrationTest {

  private static Path tmp;
  private static AegisServer server;
  private static ServerConfig config;
  private static Db db;
  private static DeliveryRepo deliveries;
  private static AlertRepo alerts;
  private static AuditRepo auditRepo;
  private static DeviceRepo devices;
  private static UserRepo users;

  private static final String D1_CODE = "DEV-A-F01-A-01";
  private static final String D2_CODE = "DEV-A-F01-A-02";
  private static String d1Id;
  private static String d2Id;

  private static TestTlsClient operator;
  private static TestTlsClient d1;
  private static TestTlsClient d2;
  private static String alert1Id;
  private static String alert2Id;
  private static String alert3Id;

  @BeforeAll
  static void startServer() throws Exception {
    tmp = Files.createTempDirectory("aegis-it");
    Path keystore = TestSupport.generateKeystore(tmp);
    config = ServerConfig.builder(tmp.resolve("aegis.db"), keystore, "changeit")
        .tlsPort(0).wsPort(0)
        .devicesPerZone(2)
        .heartbeat(800, 3)
        .retry(200, 3)
        .statusBatchMs(100)
        .argon2(1, 4096, 1)
        .build();
    server = new AegisServer(config);
    server.start();
    openRepos();
    d1Id = devices.findByCode(D1_CODE).orElseThrow().id();
    d2Id = devices.findByCode(D2_CODE).orElseThrow().id();
    operator = authOperator("admin", "admin-demo-secret");
  }

  private static void openRepos() {
    db = new Db(config.dbFile());
    deliveries = new DeliveryRepo(db);
    alerts = new AlertRepo(db);
    auditRepo = new AuditRepo(db);
    devices = new DeviceRepo(db);
    users = new UserRepo(db);
  }

  @AfterAll
  static void stopServer() throws Exception {
    for (TestTlsClient c : java.util.Arrays.asList(operator, d1, d2)) {
      if (c != null) c.close();
    }
    if (server != null) server.close();
    if (tmp != null) {
      try (Stream<Path> paths = Files.walk(tmp)) {
        paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  // ---- helpers -------------------------------------------------------------

  private static TestTlsClient authOperator(String username, String password) throws Exception {
    TestTlsClient client = new TestTlsClient(server.tlsPort(), true, true);
    Envelope hello = client.request("HELLO", Json.obj().put("clientType", "OPERATOR"), 5000);
    assertEquals("SERVER_HELLO", hello.type());
    assertEquals(2, hello.payload().get("version").asInt());
    Envelope auth = client.request("AUTH_OPERATOR",
        Json.obj().put("username", username).put("password", password), 10000);
    assertEquals("AUTH_RESULT", auth.type());
    assertTrue(auth.payload().get("ok").asBoolean(), "auth must succeed: " + auth.payload());
    return client;
  }

  private static TestTlsClient authDevice(String code) throws Exception {
    TestTlsClient client = new TestTlsClient(server.tlsPort(), true, true);
    Envelope auth = client.request("AUTH_DEVICE",
        Json.obj().put("deviceCode", code).put("credential", "device-demo-secret"), 10000);
    assertEquals("AUTH_RESULT", auth.type());
    assertTrue(auth.payload().get("ok").asBoolean(), "device auth must succeed: " + auth.payload());
    assertEquals("F01-A", auth.payload().path("zoneCode").asText());
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
    payload.put("instruction", "SIMULATION — training only. Do not take real-world action.");
    payload.put("simulation", true);
    return client.request("ALERT_PUBLISH", payload, 15000);
  }

  private static Delivery deliveryOf(String alertId, String deviceId) throws Exception {
    return deliveries.findByAlert(alertId).stream()
        .filter(d -> d.deviceId().equals(deviceId))
        .findFirst().orElseThrow();
  }

  private static boolean auditHas(String action) throws Exception {
    return auditRepo.recent(2000).stream().anyMatch(e -> e.action().equals(action));
  }

  // ---- 1. handshake + auth --------------------------------------------------

  @Test
  @Order(1)
  void helloAuthAndAuthGate() throws Exception {
    d1 = authDevice(D1_CODE);
    d2 = authDevice(D2_CODE);

    // wrong password fails closed
    TestTlsClient bad = new TestTlsClient(server.tlsPort(), true, true);
    Envelope denied = bad.request("AUTH_OPERATOR",
        Json.obj().put("username", "admin").put("password", "wrong"), 10000);
    assertFalse(denied.payload().get("ok").asBoolean());
    assertEquals("AUTH_FAILED", denied.payload().get("code").asText());
    bad.close();

    // unauthenticated commands are rejected
    TestTlsClient anon = new TestTlsClient(server.tlsPort(), true, true);
    anon.send("ALERT_PUBLISH", Json.obj().put("requestId", "anon"), null);
    Envelope error = anon.await("ERROR", 5000);
    assertEquals("NOT_AUTHENTICATED", error.payload().get("code").asText());
    anon.close();
  }

  // ---- 2. publish -> fanout -> ACK -> idempotent replay ----------------------

  @Test
  @Order(2)
  void publishFanoutAckAndIdempotentReplay() throws Exception {
    Envelope result = publish(operator, "req-it-1", 1, "A", "CRITICAL", "SIMULATION — Fire Drill");
    assertTrue(result.payload().get("ok").asBoolean(), result.payload().toString());
    alert1Id = result.payload().get("alertId").asText();
    assertEquals(1, result.payload().get("revision").asInt());
    assertEquals(2, result.payload().get("deliveryCount").asInt());

    Envelope push1 = d1.await("ALERT_PUSH", 8000);
    assertEquals(alert1Id, push1.payload().get("alertId").asText());
    String delivery1 = push1.payload().get("deliveryId").asText();
    Envelope push2 = d2.await("ALERT_PUSH", 8000);
    assertEquals(alert1Id, push2.payload().get("alertId").asText());

    // ACK_RECEIVED (durable local store) then ACK_USER (human gesture)
    Envelope ackR = d1.request("ACK_RECEIVED", Json.obj()
        .put("alertId", alert1Id).put("deliveryId", delivery1)
        .put("revision", 1).put("deviceId", d1Id), 8000);
    assertTrue(ackR.payload().get("ok").asBoolean());
    assertEquals("RECEIVED", ackR.payload().get("state").asText());
    Envelope ackU = d1.request("ACK_USER", Json.obj()
        .put("alertId", alert1Id).put("deliveryId", delivery1)
        .put("revision", 1).put("deviceId", d1Id).put("userId", "user-1"), 8000);
    assertEquals("ACKNOWLEDGED", ackU.payload().get("state").asText());
    assertEquals(DeliveryState.ACKNOWLEDGED, deliveryOf(alert1Id, d1Id).state());

    // duplicate ACK is idempotent (UNIQUE device_acks guard)
    Envelope dupAck = d1.request("ACK_RECEIVED", Json.obj()
        .put("alertId", alert1Id).put("deliveryId", delivery1)
        .put("revision", 1).put("deviceId", d1Id), 8000);
    assertTrue(dupAck.payload().get("ok").asBoolean());
    assertTrue(dupAck.payload().get("duplicate").asBoolean());

    // requestId replay returns the ORIGINAL alert, no second fanout
    Envelope replay = publish(operator, "req-it-1", 1, "A", "CRITICAL", "SIMULATION — Fire Drill");
    assertTrue(replay.payload().get("ok").asBoolean());
    assertTrue(replay.payload().get("duplicate").asBoolean());
    assertEquals(alert1Id, replay.payload().get("alertId").asText());
    assertEquals(2, deliveries.findByAlert(alert1Id).size());
  }

  // ---- 3. retry 3s/6s/12s demo (scaled) then FAILED ---------------------------

  @Test
  @Order(3)
  void silentDeviceRetriesThreeTimesThenFailed() throws Exception {
    // d2 never acked alert1: the initial push was consumed in test 2, so the
    // queue must still collect the 3 retries, each with a fresh messageId.
    // Accumulate across drains (retries arrive ~200ms apart).
    java.util.Set<String> retryIds = new java.util.HashSet<>();
    TestSupport.until("3 distinct retry ALERT_PUSH attempts on d2", () -> {
      for (Envelope p : d2.drain("ALERT_PUSH")) {
        if (p.payload().get("alertId").asText().equals(alert1Id)) {
          retryIds.add(p.messageId());
        }
      }
      return retryIds.size() >= 3;
    }, 10000);
    TestSupport.until("d2 delivery FAILED after max retries", () -> {
      try {
        return deliveryOf(alert1Id, d2Id).state() == DeliveryState.FAILED;
      } catch (Exception e) {
        return false;
      }
    }, 10000);
    assertEquals(4, deliveryOf(alert1Id, d2Id).attemptCount());
    assertTrue(server.metrics().retryCount() >= 3);
    assertTrue(server.metrics().failedCount() >= 1);
  }

  // ---- 4. ScopedRBAC on every command -----------------------------------------

  @Test
  @Order(4)
  void rbacEnforcedPerCommand() throws Exception {
    TestTlsClient floor7 = authOperator("floor7", "floor7-demo-secret");
    Envelope denied = publish(floor7, "req-it-f7-bad", 1, "A", "WARNING", "out of scope");
    assertFalse(denied.payload().get("ok").asBoolean());
    assertEquals("FORBIDDEN", denied.payload().get("code").asText());
    Envelope allowed = publish(floor7, "req-it-f7-ok", 7, "A", "WARNING", "floor 7 drill");
    assertTrue(allowed.payload().get("ok").asBoolean(), allowed.payload().toString());
    String f7Alert = allowed.payload().get("alertId").asText();
    floor7.close();

    TestTlsClient employee = authOperator("employee", "employee-demo-secret");
    Envelope empDenied = publish(employee, "req-it-emp", 1, "A", "INFO", "nope");
    assertEquals("FORBIDDEN", empDenied.payload().get("code").asText());
    employee.close();

    TestTlsClient secops = authOperator("secops", "secops-demo-secret");
    Envelope secopsOk = publish(secops, "req-it-secops", 1, "B", "WARNING", "building-wide role");
    assertTrue(secopsOk.payload().get("ok").asBoolean());
    String secopsAlert = secopsOk.payload().get("alertId").asText();
    secops.close();

    // cleanup: cancel both so they do not pollute later status assertions
    for (String alertId : List.of(f7Alert, secopsAlert)) {
      Envelope cancel = operator.request("ALERT_CANCEL",
          Json.obj().put("requestId", "req-it-clean-" + alertId).put("alertId", alertId)
              .put("reason", "cleanup"), 10000);
      assertTrue(cancel.payload().get("ok").asBoolean());
    }
    assertTrue(auditHas("AUTH_OPERATOR_SUCCESS"));
  }

  // ---- 5. cancel tombstone ------------------------------------------------------

  @Test
  @Order(5)
  void cancelTombstonesAlertAndPushesCancel() throws Exception {
    Envelope cancel = operator.request("ALERT_CANCEL", Json.obj()
        .put("requestId", "req-it-cancel-1").put("alertId", alert1Id)
        .put("reason", "Training exercise ended"), 10000);
    assertTrue(cancel.payload().get("ok").asBoolean(), cancel.payload().toString());
    Envelope cancelPush = d1.await("CANCEL_PUSH", 8000);
    assertEquals(alert1Id, cancelPush.payload().get("alertId").asText());
    assertEquals("Training exercise ended", cancelPush.payload().get("reason").asText());
    assertEquals(AlertState.CANCELLED, alerts.findById(alert1Id).orElseThrow().state());
    for (Delivery d : deliveries.findByAlert(alert1Id)) {
      assertEquals(DeliveryState.CANCELLED, d.state());
    }
    // cancel replay is idempotent
    Envelope replay = operator.request("ALERT_CANCEL", Json.obj()
        .put("requestId", "req-it-cancel-1").put("alertId", alert1Id).put("reason", "again"), 10000);
    assertTrue(replay.payload().get("duplicate").asBoolean());
  }

  // ---- 6. reconnect SYNC: latest revision + tombstones ----------------------------

  @Test
  @Order(6)
  void reconnectSyncReturnsActiveAlertsAndTombstones() throws Exception {
    Envelope result = publish(operator, "req-it-2", 1, "A", "EMERGENCY", "SIMULATION — Second Drill");
    assertTrue(result.payload().get("ok").asBoolean());
    alert2Id = result.payload().get("alertId").asText();
    Envelope push = d1.await("ALERT_PUSH", 8000);
    assertEquals(alert2Id, push.payload().get("alertId").asText());
    d1.request("ACK_RECEIVED", Json.obj()
        .put("alertId", alert2Id).put("deliveryId", push.payload().get("deliveryId").asText())
        .put("revision", 1).put("deviceId", d1Id), 8000);

    // d2 drops off the network and reconnects
    d2.close();
    TestSupport.until("stale session unregistered",
        () -> server.sessions().deviceSession(d2Id) == null, 5000);
    d2 = authDevice(D2_CODE);
    Envelope sync = d2.request("SYNC_REQUEST", Json.obj(), 10000);
    assertEquals("SYNC_RESULT", sync.type());
    JsonNode syncAlerts = sync.payload().get("alerts");
    boolean hasActive = false;
    for (JsonNode a : syncAlerts) {
      if (a.get("alertId").asText().equals(alert2Id)) {
        hasActive = true;
        assertEquals(1, a.get("revision").asInt());
        assertNotNull(a.get("deliveryId").asText());
      }
      assertFalse(a.get("alertId").asText().equals(alert1Id), "cancelled alert must not be active");
    }
    assertTrue(hasActive, "SYNC must carry the active alert at latest revision");
    boolean hasTombstone = false;
    for (JsonNode t : sync.payload().get("tombstones")) {
      if (t.get("alertId").asText().equals(alert1Id)) {
        hasTombstone = true;
        assertEquals("CANCELLED", t.get("state").asText());
      }
    }
    assertTrue(hasTombstone, "SYNC must carry the cancel tombstone");
    // resumed delivery reaches the reconnected device
    Envelope resumed = d2.await("ALERT_PUSH", 8000);
    assertEquals(alert2Id, resumed.payload().get("alertId").asText());
  }

  // ---- 7. WebSocket status/1 contract ----------------------------------------------

  @Test
  @Order(7)
  void websocketStatusFeedMatchesContract() throws Exception {
    try (TestWsClient ws = new TestWsClient(server.wsPort())) {
      JsonNode snapshot = ws.await("STATUS_SNAPSHOT", 8000);
      assertEquals(1, snapshot.get("version").asInt());
      assertEquals("AEGIS-BLD-A", snapshot.get("buildingId").asText());
      assertNotNull(snapshot.get("ts").asText());
      JsonNode floors = snapshot.get("floors");
      assertEquals(10, floors.size(), "floors 1..10");
      for (int f = 0; f < 10; f++) {
        JsonNode floor = floors.get(f);
        assertEquals(f + 1, floor.get("floorNumber").asInt());
        JsonNode zones = floor.get("zones");
        assertEquals(4, zones.size(), "4 zones per floor");
        for (int z = 0; z < 4; z++) {
          JsonNode zone = zones.get(z);
          String expectedCode = String.format("F%02d-%s", f + 1, "ABCD".charAt(z));
          assertEquals(expectedCode, zone.get("zoneCode").asText());
          assertEquals(2, zone.get("devicesTotal").asInt());
          int online = zone.get("online").asInt();
          assertTrue(online >= 0 && online <= 2);
          long received = zone.get("received").asLong();
          long acked = zone.get("acknowledged").asLong();
          long failed = zone.get("failed").asLong();
          assertTrue(acked <= received, "acknowledged must never exceed received");
          assertEquals(ZoneStatus.deriveSeverity(2, online, received, acked, failed),
              zone.get("severity").asText(), "severity must follow the normative rules");
        }
      }
      // F01-A saw a FAILED delivery in test 3 -> critical
      JsonNode f01a = floors.get(0).get("zones").get(0);
      assertTrue(f01a.get("failed").asLong() >= 1);
      assertEquals("critical", f01a.get("severity").asText());

      JsonNode metrics = snapshot.get("metrics");
      for (String key : List.of("connected", "p50", "p95", "p99", "retryCount")) {
        assertTrue(metrics.has(key), "metrics missing " + key);
      }
      assertTrue(metrics.get("connected").asInt() >= 2);
      assertTrue(metrics.get("retryCount").asLong() >= 3);

      // tombstoned alert1 never appears; alert2 does
      boolean hasAlert2 = false;
      for (JsonNode a : snapshot.get("activeAlerts")) {
        assertFalse(a.get("alertId").asText().equals(alert1Id));
        if (a.get("alertId").asText().equals(alert2Id)) {
          hasAlert2 = true;
          assertEquals("critical", a.get("severity").asText()); // EMERGENCY -> critical
          assertEquals(1, a.get("floorNumber").asInt());
          assertEquals("F01-A", a.get("zoneCode").asText());
        }
      }
      assertTrue(hasAlert2);

      // a new publish produces a STATUS_DELTA carrying the full alert list
      Envelope published = publish(operator, "req-it-ws", 2, "B", "INFO", "WS delta probe");
      assertTrue(published.payload().get("ok").asBoolean());
      String wsAlertId = published.payload().get("alertId").asText();
      TestSupport.until("STATUS_DELTA with new alert", () -> {
        JsonNode delta = ws.await("STATUS_DELTA", 3000);
        if (delta == null) return false;
        assertEquals(1, delta.get("version").asInt());
        assertEquals("AEGIS-BLD-A", delta.get("buildingId").asText());
        if (!delta.has("activeAlerts")) return false;
        for (JsonNode a : delta.get("activeAlerts")) {
          if (a.get("alertId").asText().equals(wsAlertId)) return true;
        }
        return false;
      }, 8000);
    }
  }

  // ---- 8. heartbeat suspect after 3 misses -------------------------------------------

  @Test
  @Order(8)
  void heartbeatSuspectAfterThreeMisses() throws Exception {
    TestTlsClient sleepy = new TestTlsClient(server.tlsPort(), true, false); // never answers
    Envelope auth = sleepy.request("AUTH_DEVICE", Json.obj()
        .put("deviceCode", "DEV-A-F02-C-01").put("credential", "device-demo-secret"), 10000);
    assertTrue(auth.payload().get("ok").asBoolean());
    // 400ms interval x 3 misses -> server must drop the connection
    sleepy.awaitClosed(6000);
    assertTrue(auditHas("HEARTBEAT_SUSPECT"));
    sleepy.close();
  }

  // ---- 9. slow-client policy: bounded queue 256 ---------------------------------------

  @Test
  @Order(9)
  void slowClientIsDisconnectedWhenQueueOverflows() throws Exception {
    TestTlsClient slow = new TestTlsClient(server.tlsPort(), false, false); // manual: never drains
    Envelope auth = slow.request("AUTH_DEVICE", Json.obj()
        .put("deviceCode", "DEV-A-F03-C-01").put("credential", "device-demo-secret"), 10000);
    assertTrue(auth.payload().get("ok").asBoolean());
    String slowDeviceId = auth.payload().get("deviceId").asText();
    // stop reading entirely; flood the zone with INFO alerts (8KB bodies so the
    // socket buffers absorb only a few frames and the 256 queue fills quickly)
    String padding = "x".repeat(8000);
    java.util.concurrent.atomic.AtomicInteger replies = new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < 320; i++) {
      ObjectNode payload = Json.obj();
      payload.put("requestId", "req-it-slow-" + i);
      payload.put("floorNumber", 3);
      payload.put("zoneCode", "C");
      payload.put("category", "DRILL");
      payload.put("priority", "INFO");
      payload.put("title", "slow flood " + i);
      payload.put("instruction", padding);
      payload.put("simulation", true);
      operator.send("ALERT_PUBLISH", payload, null);
      replies.addAndGet(operator.drain("COMMAND_RESULT").size());
      if (i % 50 == 49) {
        Thread.sleep(5); // let the reader answer heartbeats + the server drain
      }
    }
    TestSupport.until("slow client disconnected by policy",
        () -> server.sessions().deviceSession(slowDeviceId) == null, 20000);
    assertTrue(auditHas("SLOW_CLIENT_DISCONNECT"));
    assertTrue(server.metrics().slowClientDisconnects() >= 1);
    // drain EVERY flood reply so the next test cannot read a stale COMMAND_RESULT
    TestSupport.until("all 320 flood COMMAND_RESULTs received", () -> {
      replies.addAndGet(operator.drain("COMMAND_RESULT").size());
      return replies.get() >= 320;
    }, 30000);
    slow.close();
  }

  // ---- 10. crash recovery across a full server restart -----------------------------------

  @Test
  @Order(10)
  void crashRecoveryRedeliversAfterRestart() throws Exception {
    d1.close(); // device offline while the alert is published
    TestSupport.until("d1 offline", () -> server.sessions().deviceSession(d1Id) == null, 5000);
    Envelope result = publish(operator, "req-it-3", 1, "A", "CRITICAL", "SIMULATION — Recovery Drill");
    assertTrue(result.payload().get("ok").asBoolean());
    alert3Id = result.payload().get("alertId").asText();
    TestSupport.until("alert3 delivery parked while device offline", () -> {
      try {
        // any scheduler-owned state qualifies (RecoveryScanner rebuilds all of
        // PENDING/SENT/RETRY_SCHEDULED/FAILED after a crash)
        return deliveryOf(alert3Id, d1Id).state().isActive();
      } catch (Exception e) {
        return false;
      }
    }, 8000);
    operator.close();

    // simulate crash + restart on the SAME database
    server.close();
    server = new AegisServer(config);
    server.start();
    openRepos();
    assertTrue(auditHas("RECOVERY"), "restart must audit the recovery scan");

    operator = authOperator("admin", "admin-demo-secret");
    d1 = authDevice(D1_CODE);
    Envelope push = d1.await("ALERT_PUSH", 20000);
    assertEquals(alert3Id, push.payload().get("alertId").asText(),
        "recovered delivery must reach the reconnecting device");
    Envelope ack = d1.request("ACK_RECEIVED", Json.obj()
        .put("alertId", alert3Id).put("deliveryId", push.payload().get("deliveryId").asText())
        .put("revision", 1).put("deviceId", d1Id), 10000);
    assertTrue(ack.payload().get("ok").asBoolean());
    assertEquals(DeliveryState.RECEIVED, deliveryOf(alert3Id, d1Id).state());

    // status feed survives the restart too
    try (TestWsClient ws = new TestWsClient(server.wsPort())) {
      JsonNode snapshot = ws.await("STATUS_SNAPSHOT", 8000);
      assertEquals(1, snapshot.get("version").asInt());
      assertEquals(10, snapshot.get("floors").size());
    }
  }
}
