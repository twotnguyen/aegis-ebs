package aegis.client;

import aegis.core.RetryPolicy;
import aegis.protocol.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;

import static aegis.client.TestServer.alertPayload;
import static aegis.client.TestServer.cancelPayload;
import static aegis.client.TestUtil.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Engine logic over loopback: durable-before-ACK, dedup, separate user gesture,
 * cancel tombstones, reconnect + SYNC + pending-ACK resend, heartbeat watchdog,
 * deterministic retry exhaustion. Transport/sleeper/clock are injected.
 */
@Timeout(60)
class ClientEngineTest {

  @TempDir Path tmp;

  private InboxStore inbox;
  private AlertClientEngine engine;
  private TestServer server;
  private final RecordingListener listener = new RecordingListener();

  static final class RecordingListener implements ClientListener {
    final List<AlertRecord> fresh = new CopyOnWriteArrayList<>();
    final List<AlertRecord> suppressed = new CopyOnWriteArrayList<>();
    final List<String> cancels = new CopyOnWriteArrayList<>();
    final List<AlertClientEngine.State> states = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();

    @Override public void onAlert(AlertRecord a, boolean fresh) {
      (fresh ? this.fresh : this.suppressed).add(a);
    }
    @Override public void onCancel(String alertId, long revision, String reason) { cancels.add(alertId); }
    @Override public void onState(AlertClientEngine.State o, AlertClientEngine.State n) { states.add(n); }
    @Override public void onError(String context, Throwable error) {
      errors.add(context + (error == null ? "" : ": " + error.getMessage()));
    }
  }

  private ClientConfig config(int port, int retryMax, long retryBase, long hbTimeout) {
    return new ClientConfig("127.0.0.1", port, "DEV-TEST-01", "USER-TEST-01", "simulation-secret",
        tmp.resolve("unused-truststore.p12"), "changeit", tmp.resolve("inbox.db"), false,
        retryMax, retryBase, hbTimeout);
  }

  private AlertClientEngine startEngine(TestServer srv, ClientConfig cfg,
                                        AlertClientEngine.Sleeper sleeper, RandomGenerator jitter) {
    inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    engine = new AlertClientEngine(cfg, inbox, Socket::new, sleeper, Clock.systemUTC(), jitter, listener);
    engine.start();
    return engine;
  }

  private AlertClientEngine startEngine(TestServer srv) {
    return startEngine(srv, config(srv.port(), 8, 5, 10_000), Thread::sleep, new Random(1));
  }

  @AfterEach void tearDown() throws Exception {
    if (engine != null) engine.close();
    if (server != null) server.close();
    if (inbox != null) inbox.close();
  }

  private AlertRecord storedAlert(String alertId, long rev, String messageId) {
    String now = Instant.now().toString();
    return new AlertRecord(alertId, rev, "DLV-" + alertId + "-" + rev, "INC-1", "B01", "FIRE",
        "CRITICAL", "SIMULATION — drill", "Training only.", true, "Immediate", "Severe", "Observed",
        Instant.now().plusSeconds(3600).toString(), messageId, null, now, now);
  }

  @Test void durableCommitAndUserNotificationPrecedeAckReceived() throws Exception {
    List<String> violations = new CopyOnWriteArrayList<>();
    List<Envelope> acks = new CopyOnWriteArrayList<>();
    server = new TestServer(TestServer.pushScript(
        List.of(alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — Fire Drill")), List.of(), false));
    server.onFrame = e -> {
      if (!"ACK_RECEIVED".equals(e.type())) return;
      acks.add(e);
      if (inbox.find("ALT-1").isEmpty()) violations.add("ACK before durable commit");
      if (listener.fresh.isEmpty()) violations.add("ACK before user notification");
    };
    startEngine(server);
    await("ACK_RECEIVED on wire", 10_000, () -> !acks.isEmpty());
    await("ACK marked sent in inbox", 5_000, () -> inbox.pendingReceivedAcks().isEmpty());

    assertTrue(violations.isEmpty(), "ordering violations: " + violations);
    Envelope ack = acks.get(0);
    assertEquals("push-0-0", ack.correlationId(), "ACK correlates to the push messageId");
    JsonNode p = ack.payload();
    assertEquals("ALT-1", p.get("alertId").asText());
    assertEquals("DLV-1", p.get("deliveryId").asText());
    assertEquals(1, p.get("revision").asLong());
    assertEquals("DEV-TEST-01", p.get("deviceId").asText());
    assertTrue(p.hasNonNull("ackAt"));
    assertEquals(1, listener.fresh.size());
    assertEquals(InboxStore.StoreResult.DUPLICATE, inbox.store(storedAlert("ALT-1", 1, "push-0-0")),
        "durable row already exists");
  }

  @Test void duplicatePushIsReAckedButNeverRedisplayed() throws Exception {
    JsonNode alert = alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — drill");
    server = new TestServer(TestServer.pushScript(List.of(alert, alert), List.of(), false));
    startEngine(server);
    await("two ACK_RECEIVED (idempotent re-ACK)", 10_000, () -> server.framesOfType("ACK_RECEIVED").size() == 2);
    assertEquals(1, listener.fresh.size(), "duplicate must not pop up twice");
    assertEquals(1, listener.suppressed.size());
    assertEquals(1, inbox.count());
  }

  @Test void staleRevisionSuppressedForDisplay() throws Exception {
    server = new TestServer(TestServer.pushScript(List.of(
        alertPayload("ALT-1", 2, "DLV-1-r2", "SIMULATION — rev2"),
        alertPayload("ALT-1", 1, "DLV-1-r1", "SIMULATION — rev1")), List.of(), false));
    startEngine(server);
    await("stale push handled", 10_000, () -> listener.suppressed.size() == 1);
    assertEquals(1, listener.fresh.size());
    assertEquals(2, listener.fresh.get(0).revision(), "newest revision wins");
    assertEquals(2, inbox.find("ALT-1").orElseThrow().revision());
    // stale push is still re-ACKed (ACK only advances state; server stops retrying)
    await("re-ACK of stale delivery", 5_000, () -> server.framesOfType("ACK_RECEIVED").size() == 2);
  }

  @Test void reconnectSendsSyncWithKnownRevisionsAndDedupsRedelivery() throws Exception {
    JsonNode alert1 = alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — one");
    JsonNode alert2 = alertPayload("ALT-2", 1, "DLV-2", "SIMULATION — two");
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      if (idx == 0) {
        s.pushAlert(alert1, "push-0-0");
        s.rstClose(); // lose the ACK: connection dies right after the push
      } else {
        s.recordLoop(List.of(alert1, alert2), false); // SYNC redelivers alert1 + new alert2
      }
    });
    startEngine(server);
    await("reconnect + SYNC_REQUEST", 15_000, () -> server.connections.get() >= 2
        && !server.framesOfType("SYNC_REQUEST").isEmpty());
    await("alert2 acked after sync", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "ALT-2".equals(e.payload().path("alertId").asText())));
    await("alert1 re-acked after sync redelivery", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "ALT-1".equals(e.payload().path("alertId").asText())));

    Envelope sync = server.framesOfType("SYNC_REQUEST").get(0);
    assertEquals("DEV-TEST-01", sync.payload().get("deviceId").asText());
    assertEquals(1, sync.payload().get("knownRevisions").get("ALT-1").asLong(),
        "SYNC must carry durably-known revisions");
    assertEquals(List.of("ALT-1", "ALT-2"), listener.fresh.stream().map(AlertRecord::alertId).toList(),
        "redelivered alert1 must not pop up again");
    assertEquals(2, inbox.count());
    assertTrue(listener.states.contains(AlertClientEngine.State.RECONNECTING));
    assertTrue(listener.states.contains(AlertClientEngine.State.ONLINE));
  }

  @Test void crashBetweenCommitAndAckResendsPendingAckOnReconnect() throws Exception {
    // Simulate a previous session that committed the alert but died before the ACK.
    inbox = new InboxStore(tmp.resolve("inbox.db"), "DEV-TEST-01");
    inbox.store(storedAlert("ALT-CRASH", 1, "push-crash"));
    inbox.close();

    server = new TestServer(TestServer.pushScript(List.of(), List.of(), false));
    startEngine(server);
    await("pending ACK_RECEIVED re-sent", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "push-crash".equals(e.correlationId())));
    Envelope ack = server.framesOfType("ACK_RECEIVED").get(0);
    assertEquals("ALT-CRASH", ack.payload().get("alertId").asText());
    assertEquals("DLV-ALT-CRASH-1", ack.payload().get("deliveryId").asText());
    await("pending drained", 5_000, () -> inbox.pendingReceivedAcks().isEmpty());
    assertTrue(listener.fresh.isEmpty(), "no new push happened");
  }

  @Test void userAckIsASeparateHumanGesture() throws Exception {
    server = new TestServer(TestServer.pushScript(
        List.of(alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — drill")), List.of(), false));
    startEngine(server);
    await("alert durably stored", 10_000, () -> inbox.find("ALT-1").isPresent());
    await("transport ACK sent", 5_000, () -> !server.framesOfType("ACK_RECEIVED").isEmpty());

    assertTrue(engine.ackUser("ALT-1", "USER-TEST-01"));
    await("ACK_USER on wire", 5_000, () -> !server.framesOfType("ACK_USER").isEmpty());
    Envelope userAck = server.framesOfType("ACK_USER").get(0);
    assertEquals("push-0-0", userAck.correlationId());
    assertEquals("USER-TEST-01", userAck.payload().get("userId").asText());
    assertEquals("ACKNOWLEDGE", userAck.payload().get("action").asText());
    assertEquals("DEV-TEST-01", userAck.payload().get("deviceId").asText());
    assertEquals(1, userAck.payload().get("revision").asLong());
    await("user ACK marked sent", 5_000, () -> inbox.pendingUserAcks().isEmpty());

    assertFalse(engine.ackUser("ALT-1", "USER-TEST-01"), "already acknowledged — ACK only advances");
    assertFalse(engine.ackUser("ALT-UNKNOWN", "USER-TEST-01"));
    assertEquals(1, server.framesOfType("ACK_USER").size());
    assertEquals(1, server.framesOfType("ACK_RECEIVED").size(), "ACK_RECEIVED is not implied by ACK_USER");
  }

  @Test void offlineUserGestureIsDurableAndSentAfterReconnect() throws Exception {
    server = new TestServer(TestServer.pushScript(List.of(), List.of(), false));
    ClientConfig cfg = config(server.port(), 8, 5, 10_000);
    inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    inbox.store(storedAlert("ALT-1", 1, "push-prev"));
    engine = new AlertClientEngine(cfg, inbox, Socket::new, Thread::sleep, Clock.systemUTC(), new Random(1), listener);

    // Gesture while offline: durable mark succeeds, frame cannot be sent yet.
    assertTrue(engine.ackUser("ALT-1", "USER-OFFLINE"));
    assertEquals(1, inbox.pendingUserAcks().size());
    assertTrue(server.framesOfType("ACK_USER").isEmpty());

    engine.start();
    await("queued ACK_USER sent once online", 10_000, () -> server.framesOfType("ACK_USER").stream()
        .anyMatch(e -> "USER-OFFLINE".equals(e.payload().path("userId").asText())
            && "push-prev".equals(e.correlationId())));
    await("pending user ACK drained", 5_000, () -> inbox.pendingUserAcks().isEmpty());
  }

  @Test void cancelTombstoneRemovesDisplayAndBlocksStaleRedisplay() throws Exception {
    JsonNode alert = alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — drill");
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      s.pushAlert(alert, "push-0-0");
      Envelope ack = s.read();
      server.frames.add(ack);
      s.write("CANCEL_PUSH", "req-cancel", cancelPayload("ALT-1", 2, "exercise ended"), "cancel-0");
      Envelope ackCancel = s.read();
      server.frames.add(ackCancel);
      s.pushAlert(alert, "push-0-1"); // stale redelivery after cancel
      s.recordLoop(List.of(), false);
    });
    startEngine(server);
    await("cancel applied", 10_000, () -> !listener.cancels.isEmpty());
    await("ACK_CANCEL on wire", 5_000, () -> !server.framesOfType("ACK_CANCEL").isEmpty());
    Envelope ackCancel = server.framesOfType("ACK_CANCEL").get(0);
    assertEquals("cancel-0", ackCancel.correlationId());
    assertEquals(2, ackCancel.payload().get("revision").asLong());
    assertTrue(ackCancel.payload().hasNonNull("operationId"));
    assertEquals("DLV-1", ackCancel.payload().get("deliveryId").asText(),
        "ACK_CANCEL echoes the stored delivery so the server can match its delivery row");

    assertTrue(inbox.isCancelled("ALT-1"));
    assertTrue(inbox.activeAlerts().isEmpty(), "tombstone prevents redisplay");
    assertFalse(engine.ackUser("ALT-1", "USER-TEST-01"), "no user ack on cancelled alert");

    await("stale post-cancel push suppressed", 10_000, () -> listener.suppressed.size() == 1);
    assertEquals(1, listener.fresh.size(), "stale push must not pop up again");
    assertEquals(1, listener.cancels.size());
  }

  @Test void syncResultAppliesAlertsAndCancels() throws Exception {
    JsonNode alert = alertPayload("ALT-S", 1, "DLV-S", "SIMULATION — synced");
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      Envelope sync = s.read();
      server.frames.add(sync);
      assertEquals("SYNC_REQUEST", sync.type());
      ObjectNode p = TestServer.MAPPER.createObjectNode();
      p.putArray("alerts").add(alert);
      p.putArray("cancels").add(cancelPayload("ALT-S", 2, "synced cancel"));
      p.put("syncedAt", Instant.now().toString());
      s.write("SYNC_RESULT", sync.messageId(), p);
      s.recordLoop(List.of(), false);
    });
    startEngine(server);
    await("engine online", 10_000, engine::isOnline);
    assertTrue(engine.requestSync(), "manual sync while online");
    await("synced alert acked", 10_000, () -> !server.framesOfType("ACK_RECEIVED").isEmpty());
    await("synced cancel acked", 10_000, () -> !server.framesOfType("ACK_CANCEL").isEmpty());
    assertEquals(1, listener.fresh.size());
    assertEquals(List.of("ALT-S"), listener.cancels);
    assertTrue(inbox.isCancelled("ALT-S"));
  }

  @Test void heartbeatIsAnswered() throws Exception {
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      ObjectNode p = TestServer.MAPPER.createObjectNode();
      p.put("serverTime", Instant.now().toString());
      s.write("HEARTBEAT", null, p, "hb-0");
      s.recordLoop(List.of(), false);
    });
    startEngine(server);
    await("HEARTBEAT_ACK", 10_000, () -> !server.framesOfType("HEARTBEAT_ACK").isEmpty());
    Envelope ack = server.framesOfType("HEARTBEAT_ACK").get(0);
    assertEquals("hb-0", ack.correlationId());
    assertEquals("DEV-TEST-01", ack.payload().get("deviceId").asText());
  }

  @Test void authRejectionFailsFastWithoutRetry() throws Exception {
    server = new TestServer((idx, s) -> s.handshake(false));
    startEngine(server);
    await("FAILED state", 10_000, () -> engine.state() == AlertClientEngine.State.FAILED);
    assertEquals(1, server.connections.get(), "auth rejection is permanent — no retry");
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("authentication rejected")));
  }

  @Test void retryExhaustionUsesDeterministicExponentialBackoff() throws IOException {
    int deadPort;
    try (ServerSocket ss = new ServerSocket(0)) { deadPort = ss.getLocalPort(); }
    List<Long> delays = new CopyOnWriteArrayList<>();
    ClientConfig cfg = config(deadPort, 3, 1000, 10_000);
    inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    engine = new AlertClientEngine(cfg, inbox, Socket::new, delays::add, Clock.systemUTC(), new Random(42), listener);
    engine.start();
    await("FAILED after maxAttempts", 10_000, () -> engine.state() == AlertClientEngine.State.FAILED);

    RetryPolicy reference = new RetryPolicy(3, 1000, new Random(42));
    assertEquals(List.of(reference.delayMs(0), reference.delayMs(1)), delays,
        "3s/6s-style exponential backoff + seeded jitter, maxAttempts includes first attempt");
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("retry exhausted")));
  }

  @Test void heartbeatWatchdogReconnectsAfterThreeMissedIntervals() throws Exception {
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      // Stay silent: swallow everything (incl. client HEARTBEATs), never reply.
      while (!s.socket.isClosed()) { s.read(); }
    });
    startEngine(server, config(server.port(), 8, 5, 200), Thread::sleep, new Random(1));
    await("watchdog triggered reconnect (3 x 200ms silence)", 15_000, () -> server.connections.get() >= 2);
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("heartbeat suspect")));
  }

  @Test void clientSendsPeriodicHeartbeats() throws Exception {
    server = new TestServer(TestServer.pushScript(List.of(), List.of(), false));
    startEngine(server, config(server.port(), 8, 5, 300), Thread::sleep, new Random(1));
    await("client-initiated HEARTBEAT", 10_000, () -> !server.framesOfType("HEARTBEAT").isEmpty());
    Envelope hb = server.framesOfType("HEARTBEAT").get(0);
    assertEquals("DEV-TEST-01", hb.payload().get("deviceId").asText());
    await("server HEARTBEAT_ACK processed", 5_000, () -> engine.isOnline());
  }

  // ---- M1 regression: a malformed frame must not kill the controller thread ----

  @Test void malformedAlertPushIsDroppedAndControllerSurvives() throws Exception {
    ObjectNode bad = alertPayload("ALT-BAD", 1, "DLV-BAD", "SIMULATION — missing title");
    bad.remove("title"); // AlertRecord.requireText throws IllegalArgumentException
    server = new TestServer(TestServer.pushScript(List.of(bad,
        alertPayload("ALT-OK", 1, "DLV-OK", "SIMULATION — valid after malformed")), List.of(), false));
    startEngine(server);
    await("valid push after malformed frame is ACKed", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "ALT-OK".equals(e.payload().path("alertId").asText())));
    assertEquals(AlertClientEngine.State.ONLINE, engine.state(), "controller must survive a malformed frame");
    assertEquals(1, server.connections.get(), "malformed frame must not drop the connection");
    assertEquals(1, listener.fresh.size(), "only the valid push pops up");
    assertEquals("ALT-OK", listener.fresh.get(0).alertId());
    assertTrue(inbox.find("ALT-BAD").isEmpty(), "malformed push must not be stored");
    assertEquals(1, inbox.count());
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("malformed frame")),
        "the dropped frame must surface via listenerError: " + listener.errors);
  }

  @Test void malformedRevisionPushIsDroppedAndControllerSurvives() throws Exception {
    ObjectNode bad = alertPayload("ALT-BAD", 1, "DLV-BAD", "SIMULATION — revision zero");
    bad.put("revision", 0); // revision must be a positive integer
    server = new TestServer(TestServer.pushScript(List.of(bad,
        alertPayload("ALT-OK", 1, "DLV-OK", "SIMULATION — valid after malformed")), List.of(), false));
    startEngine(server);
    await("valid push after malformed revision is ACKed", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "ALT-OK".equals(e.payload().path("alertId").asText())));
    assertEquals(AlertClientEngine.State.ONLINE, engine.state());
    assertEquals(1, server.connections.get());
    assertEquals(1, listener.fresh.size());
    assertEquals(1, inbox.count());
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("malformed frame")));
  }

  @Test void malformedSyncResultEntryIsDroppedAndControllerSurvives() throws Exception {
    ObjectNode bad = alertPayload("ALT-BAD", 1, "DLV-BAD", "SIMULATION — missing deliveryId");
    bad.remove("deliveryId"); // SYNC_RESULT.alerts entry fails AlertRecord.fromPayload
    server = new TestServer((idx, s) -> {
      s.handshake(true);
      Envelope sync = s.read();
      server.frames.add(sync);
      assertEquals("SYNC_REQUEST", sync.type());
      ObjectNode p = TestServer.MAPPER.createObjectNode();
      p.putArray("alerts").add(bad);
      p.putArray("cancels");
      s.write("SYNC_RESULT", sync.messageId(), p);
      s.pushAlert(alertPayload("ALT-OK", 1, "DLV-OK", "SIMULATION — valid after bad sync"), "push-ok");
      s.recordLoop(List.of(), false);
    });
    startEngine(server);
    await("engine online", 10_000, engine::isOnline);
    assertTrue(engine.requestSync());
    await("valid push after malformed SYNC_RESULT is ACKed", 10_000, () -> server.framesOfType("ACK_RECEIVED").stream()
        .anyMatch(e -> "ALT-OK".equals(e.payload().path("alertId").asText())));
    assertEquals(AlertClientEngine.State.ONLINE, engine.state(), "controller must survive a malformed SYNC_RESULT");
    assertEquals(1, server.connections.get());
    assertEquals(1, listener.fresh.size());
    assertEquals("ALT-OK", listener.fresh.get(0).alertId());
    assertTrue(inbox.find("ALT-BAD").isEmpty());
    assertEquals(1, inbox.count());
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("malformed frame")));
  }

  // ---- L1 regression: watchdog/soTimeout anchor to the negotiated interval ----

  @Test void watchdogAnchorsToNegotiatedIntervalOnHealthyServer() throws Exception {
    // Healthy server advertises 700ms (> 3 x cfg timeout 200ms) and answers every
    // HEARTBEAT: the link must stay up — no false suspects, no reconnect churn.
    server = new TestServer((idx, s) -> {
      s.handshake(true, 700);
      s.recordLoop(List.of(), false);
    });
    startEngine(server, config(server.port(), 8, 5, 200), Thread::sleep, new Random(1));
    await("four client HEARTBEATs at the negotiated cadence", 10_000,
        () -> server.framesOfType("HEARTBEAT").size() >= 4);
    assertEquals(1, server.connections.get(),
        "healthy server at the negotiated interval must not trigger false suspects");
    assertTrue(engine.isOnline());
    assertTrue(listener.errors.stream().noneMatch(e -> e.contains("heartbeat suspect")),
        "unexpected suspects: " + listener.errors);
  }

  @Test void watchdogSuspectsAfterThreeNegotiatedIntervalsWhenServerGoesSilent() throws Exception {
    // Negotiated 300ms with cfg timeout 10s: silence must be judged against the
    // negotiated interval (suspect ~900ms), not the local cfg (would take 30s).
    server = new TestServer((idx, s) -> {
      s.handshake(true, 300);
      while (!s.socket.isClosed()) { s.read(); } // swallow everything, never reply
    });
    startEngine(server, config(server.port(), 8, 5, 10_000), Thread::sleep, new Random(1));
    await("watchdog reconnect after 3 x negotiated 300ms silence", 8_000, () -> server.connections.get() >= 2);
    assertTrue(listener.errors.stream().anyMatch(e -> e.contains("heartbeat suspect")));
  }

  @Test void stopIsCleanAndInboxRemainsUsable() throws Exception {
    server = new TestServer(TestServer.pushScript(
        List.of(alertPayload("ALT-1", 1, "DLV-1", "SIMULATION — drill")), List.of(), false));
    startEngine(server);
    await("alert stored", 10_000, () -> inbox.find("ALT-1").isPresent());
    engine.stop();
    assertEquals(AlertClientEngine.State.STOPPED, engine.state());
    engine.close(); // idempotent
    assertTrue(inbox.find("ALT-1").isPresent(), "durable inbox outlives the connection");
    assertEquals(1, inbox.activeAlerts().size());
  }
}
