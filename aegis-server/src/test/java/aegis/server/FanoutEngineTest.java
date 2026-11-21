package aegis.server;

import aegis.core.DeviceLocation;
import aegis.core.RetryPolicy;
import aegis.persistence.AlertRepo;
import aegis.persistence.AuditRepo;
import aegis.persistence.Db;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.DeliveryState;
import aegis.persistence.MigrationRunner;
import aegis.persistence.RecoveryScanner;
import aegis.persistence.TransactionalPublisher;
import aegis.persistence.UserRepo;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.Priority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-level tests on real SQLite + loopback socket sessions (no TLS):
 * publish -> dispatch -> ACK stops retries -> silent device retries 3x then
 * FAILED, idempotent enqueue, superseded revisions, offline resume, recovery.
 */
class FanoutEngineTest {

  /** Zero jitter so retry windows are exactly 150/300/450ms in tests. */
  private static final RandomGenerator ZERO_JITTER = new RandomGenerator() {
    @Override public int nextInt() { return 0; }
    @Override public long nextLong() { return 0; }
    @Override public long nextLong(long bound) { return 0; }
    @Override public int nextInt(int bound) { return 0; }
  };

  @TempDir
  Path tmp;

  private Db db;
  private AlertRepo alerts;
  private DeliveryRepo deliveries;
  private ServerStore store;
  private SessionRegistry sessions;
  private MetricsRegistry metrics;
  private ScheduledExecutorService timers;
  private java.util.concurrent.ExecutorService io;
  private FanoutEngine engine;
  private TransactionalPublisher publisher;
  private String adminId;
  private final List<String> failedDevices = new ArrayList<>();
  private final List<String> pushedDevices = new ArrayList<>();
  private final List<TestSupport.SocketPair> pairs = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    db = new Db(tmp.resolve("engine.db"));
    new MigrationRunner(db).migrate();
    Clock clock = Clock.systemUTC();
    alerts = new AlertRepo(db, clock);
    deliveries = new DeliveryRepo(db, clock);
    store = new ServerStore(db, clock, alerts, deliveries);
    store.seedWorld("AEGIS-BLD-A", 1, new Argon2idHasher(1, 4096, 1));
    adminId = new UserRepo(db).findByUsername("admin").orElseThrow().id();
    sessions = new SessionRegistry();
    metrics = new MetricsRegistry();
    timers = Executors.newScheduledThreadPool(2);
    io = Executors.newVirtualThreadPerTaskExecutor();
    engine = newEngine();
    engine.start(io);
    publisher = new TransactionalPublisher(db, clock, engine);
  }

  private FanoutEngine newEngine() {
    StatusNotifier recorder = new StatusNotifier() {
      @Override public void pushWritten(String deviceId) { pushedDevices.add(deviceId); }
      @Override public void ackReceived(String deviceId) { }
      @Override public void deliveryFailed(String deviceId) { failedDevices.add(deviceId); }
      @Override public void alertsChanged() { }
      @Override public void connectivityChanged() { }
    };
    return new FanoutEngine(alerts, deliveries, sessions,
        new RetryPolicy(3, 150, ZERO_JITTER), timers, Clock.systemUTC(), metrics,
        new AuditService(new AuditRepo(db)), recorder);
  }

  @AfterEach
  void teardown() {
    engine.shutdown();
    sessions.closeAll();
    for (TestSupport.SocketPair p : pairs) p.close();
    io.shutdown();
    try {
      io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    timers.shutdownNow();
  }

  private record Dev(String deviceId, ClientSession session, Socket peer) {}

  private Dev connectDevice(String code) throws Exception {
    var device = new aegis.persistence.DeviceRepo(db).findByCode(code).orElseThrow();
    ServerStore.DeviceInfo info = store.deviceInfo(device.id()).orElseThrow();
    TestSupport.SocketPair pair = TestSupport.socketPair();
    pairs.add(pair);
    ClientSession session = new ClientSession(Json.uuid(), pair.sessionSide(), new FrameCodecImpl(),
        256, Clock.systemUTC(), io, sessions::unregister, ClientSession::close);
    sessions.register(session);
    session.bindDevice(device.id(),
        new DeviceLocation(device.id(), info.buildingId(), info.floorId(), info.zoneCode()),
        info.floorNumber(), info.zoneCode());
    sessions.deviceOnline(session);
    return new Dev(device.id(), session, pair.peerSide());
  }

  private TransactionalPublisher.Result publish(String requestId, List<String> deviceIds)
      throws Exception {
    return publisher.publish(new TransactionalPublisher.Request(requestId, "AEGIS-BLD-A", adminId,
        "FIRE", Priority.CRITICAL, "SIMULATION — Fire Drill", "Training only.", "{}", null, deviceIds));
  }

  private static Envelope readFrame(Socket peer, int timeoutMs) throws Exception {
    peer.setSoTimeout(timeoutMs);
    try {
      return new FrameCodecImpl().readFrame(new DataInputStream(peer.getInputStream()));
    } catch (SocketTimeoutException e) {
      return null;
    }
  }

  /** SQLException-safe state probe for polling awaits. */
  private boolean stateIs(String deliveryId, DeliveryState expected) {
    try {
      return deliveries.findById(deliveryId).map(d -> d.state() == expected).orElse(false);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean stateActive(String deliveryId) {
    try {
      return deliveries.findById(deliveryId).map(d -> d.state().isActive()).orElse(false);
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void publishDispatchesPushAndMarksSent() throws Exception {
    Dev dev = connectDevice("DEV-A-F01-A-01");
    var result = publish("req-push-1", List.of(dev.deviceId()));
    Envelope push = readFrame(dev.peer(), 3000);
    assertNotNull(push, "device must receive ALERT_PUSH");
    assertEquals("ALERT_PUSH", push.type());
    assertEquals(result.alert().id(), push.payload().get("alertId").asText());
    assertEquals(1, push.payload().get("revision").asInt());
    assertEquals(result.deliveries().get(0).id(), push.payload().get("deliveryId").asText());
    assertEquals("CRITICAL", push.payload().get("priority").asText());
    Delivery d0 = result.deliveries().get(0);
    // the frame hits the socket before the dispatcher commits SENT: poll briefly
    TestSupport.until("delivery marked SENT", () -> stateIs(d0.id(), DeliveryState.SENT), 3000);
    Delivery d = deliveries.findById(d0.id()).orElseThrow();
    assertEquals(DeliveryState.SENT, d.state());
    assertEquals(1, d.attemptCount());
    assertTrue(pushedDevices.contains(dev.deviceId()));
  }

  @Test
  void ackReceivedStopsRetryCycle() throws Exception {
    Dev dev = connectDevice("DEV-A-F01-A-01");
    var result = publish("req-ack-1", List.of(dev.deviceId()));
    Delivery d0 = result.deliveries().get(0);
    assertNotNull(readFrame(dev.peer(), 3000));
    deliveries.recordAck(dev.deviceId(), d0.alertId(), 1, d0.id(), aegis.persistence.AckKind.RECEIVED, null);
    engine.onAck(d0.alertId(), d0.id(), dev.deviceId(), aegis.persistence.AckKind.RECEIVED,
        java.time.Instant.now());
    assertEquals(DeliveryState.RECEIVED, deliveries.findById(d0.id()).orElseThrow().state());
    // well past the 150ms + 300ms retry windows: nothing more may arrive
    assertNull(readFrame(dev.peer(), 900), "acked delivery must not be retried");
    assertEquals(1, deliveries.findById(d0.id()).orElseThrow().attemptCount());
  }

  @Test
  void silentDeviceGetsThreeRetriesThenFailed() throws Exception {
    Dev dev = connectDevice("DEV-A-F01-B-01");
    var result = publish("req-retry-1", List.of(dev.deviceId()));
    Delivery d0 = result.deliveries().get(0);
    List<String> messageIds = new ArrayList<>();
    Envelope first = readFrame(dev.peer(), 3000);
    assertNotNull(first);
    messageIds.add(first.messageId());
    for (int i = 0; i < 3; i++) {
      Envelope retry = readFrame(dev.peer(), 4000);
      assertNotNull(retry, "retry " + (i + 1) + " must arrive");
      assertEquals("ALERT_PUSH", retry.type());
      messageIds.add(retry.messageId());
    }
    assertEquals(4, messageIds.stream().distinct().count(), "each attempt uses a fresh messageId");
    TestSupport.until("delivery FAILED after max retries",
        () -> stateIs(d0.id(), DeliveryState.FAILED), 6000);
    Delivery failed = deliveries.findById(d0.id()).orElseThrow();
    assertEquals(4, failed.attemptCount(), "initial send + 3 retries");
    assertTrue(metrics.retryCount() >= 3);
    assertTrue(failedDevices.contains(dev.deviceId()));
    // no further pushes after FAILED
    assertNull(readFrame(dev.peer(), 700));
  }

  @Test
  void doubleEnqueueBeforeDispatchDeliversOnce() throws Exception {
    // engine NOT started yet: enqueue twice, then start -> exactly one push
    FanoutEngine idle = newEngine();
    TransactionalPublisher pub = new TransactionalPublisher(db, idle);
    Dev dev = connectDevice("DEV-A-F01-C-01");
    var result = pub.publish(new TransactionalPublisher.Request("req-dup-1", "AEGIS-BLD-A", adminId,
        "FIRE", Priority.CRITICAL, "t", "b", "{}", null, List.of(dev.deviceId())));
    idle.enqueue(result.deliveries()); // duplicate enqueue (e.g. recovery race)
    idle.start(io);
    assertNotNull(readFrame(dev.peer(), 3000));
    // ack so retries cannot masquerade as duplicates
    Delivery d0 = result.deliveries().get(0);
    deliveries.recordAck(dev.deviceId(), d0.alertId(), 1, d0.id(), aegis.persistence.AckKind.RECEIVED, null);
    idle.onAck(d0.alertId(), d0.id(), dev.deviceId(), aegis.persistence.AckKind.RECEIVED,
        java.time.Instant.now());
    assertNull(readFrame(dev.peer(), 600), "duplicate enqueue must not produce a second push");
    idle.shutdown();
  }

  @Test
  void supersededRevisionCancelsStaleDelivery() throws Exception {
    Dev dev = connectDevice("DEV-A-F01-D-01");
    var result = publish("req-sup-1", List.of(dev.deviceId()));
    Delivery d0 = result.deliveries().get(0);
    assertNotNull(readFrame(dev.peer(), 3000)); // rev1 sent, retry window 150ms
    alerts.addRevision(result.alert().id(), 2, "updated", "new body", "{}", adminId);
    alerts.setCurrentRevision(result.alert().id(), 2);
    TestSupport.until("stale rev1 delivery cancelled",
        () -> stateIs(d0.id(), DeliveryState.CANCELLED), 4000);
    assertNull(readFrame(dev.peer(), 500), "superseded revision must not be retried");
  }

  @Test
  void offlineDeviceSchedulesRetryAndResumesOnReconnect() throws Exception {
    var device = new aegis.persistence.DeviceRepo(db).findByCode("DEV-A-F02-A-01").orElseThrow();
    var result = publish("req-off-1", List.of(device.id()));
    Delivery d0 = result.deliveries().get(0);
    TestSupport.until("offline delivery RETRY_SCHEDULED",
        () -> stateIs(d0.id(), DeliveryState.RETRY_SCHEDULED), 3000);
    Dev dev = connectDevice("DEV-A-F02-A-01");
    engine.resumeDevice(dev.deviceId());
    Envelope push = readFrame(dev.peer(), 3000);
    assertNotNull(push, "reconnected device must receive the pending alert");
    assertEquals(d0.alertId(), push.payload().get("alertId").asText());
  }

  @Test
  void recoveryScannerRebuildsEngineAfterCrash() throws Exception {
    var device = new aegis.persistence.DeviceRepo(db).findByCode("DEV-A-F02-B-01").orElseThrow();
    var result = publish("req-rec-1", List.of(device.id()));
    Delivery d0 = result.deliveries().get(0);
    TestSupport.until("delivery scheduled while offline",
        () -> stateActive(d0.id()), 3000);
    engine.shutdown(); // simulate crash: in-memory queue lost
    FanoutEngine reborn = newEngine();
    int recovered = new RecoveryScanner(db).recover(reborn::recoverEnqueue);
    assertTrue(recovered >= 1);
    reborn.start(io);
    Dev dev = connectDevice("DEV-A-F02-B-01");
    reborn.resumeDevice(dev.deviceId());
    Envelope push = readFrame(dev.peer(), 3000);
    assertNotNull(push, "recovered delivery must reach the reconnecting device");
    assertEquals(d0.alertId(), push.payload().get("alertId").asText());
    reborn.shutdown();
  }
}
