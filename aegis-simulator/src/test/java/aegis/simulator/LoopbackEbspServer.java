package aegis.simulator;

import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal EBSP/2.0 TLS loopback server for smoke tests: HELLO/AUTH handshake,
 * pushes K alerts per connection, answers SYNC_REQUEST with one extra alert,
 * sends one HEARTBEAT, records every ACK. Test scope only — NOT the real server.
 */
final class LoopbackEbspServer implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final FrameCodec codec = new FrameCodecImpl();
  private final SSLServerSocket serverSocket;
  private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
  private final int alertsPerConnection;
  private final boolean authOk;

  final AtomicInteger connections = new AtomicInteger();
  final AtomicInteger acksReceived = new AtomicInteger();
  final AtomicInteger ackCancels = new AtomicInteger();
  final AtomicInteger syncRequests = new AtomicInteger();
  final AtomicInteger heartbeatAcks = new AtomicInteger();
  final AtomicInteger alertsPushed = new AtomicInteger();
  final List<String> ackLog = new CopyOnWriteArrayList<>(); // deviceId|alertId|messageId
  final List<String> deviceIds = new CopyOnWriteArrayList<>();
  volatile boolean sawTlsSocket;

  LoopbackEbspServer(SSLContext serverTls, int alertsPerConnection) throws IOException {
    this(serverTls, alertsPerConnection, true);
  }

  /** authOk=false: AUTH_RESULT ok=false (permanent rejection fixture for fail-fast tests). */
  LoopbackEbspServer(SSLContext serverTls, int alertsPerConnection, boolean authOk) throws IOException {
    this.alertsPerConnection = alertsPerConnection;
    this.authOk = authOk;
    this.serverSocket = (SSLServerSocket) serverTls.getServerSocketFactory()
        .createServerSocket(0, 256, InetAddress.getByName("127.0.0.1"));
    exec.submit(() -> {
      while (!serverSocket.isClosed()) {
        try {
          Socket s = serverSocket.accept();
          connections.incrementAndGet();
          exec.submit(() -> handle(s));
        } catch (IOException closed) {
          return;
        }
      }
    });
  }

  int port() { return serverSocket.getLocalPort(); }

  private void handle(Socket s) {
    try (s) {
      if (s instanceof javax.net.ssl.SSLSocket) sawTlsSocket = true;
      s.setSoTimeout(15_000);
      String deviceId = handshake(s);
      if (deviceId == null) return; // auth rejected: close without pushing anything
      deviceIds.add(deviceId);
      for (int k = 1; k <= alertsPerConnection; k++) {
        ObjectNode p = alertPayload("ALT-SIM-" + k, 1, "DLV-" + deviceId + "-" + k,
            "SIMULATION — fleet drill " + k);
        write(s, "ALERT_PUSH", "req-sim", p, "push-" + deviceId + "-" + k);
        alertsPushed.incrementAndGet();
      }
      ObjectNode hb = MAPPER.createObjectNode();
      hb.put("serverTime", Instant.now().toString());
      write(s, "HEARTBEAT", null, hb, "hb-" + deviceId);

      while (!s.isClosed()) {
        Envelope e;
        try {
          e = codec.readFrame(s.getInputStream());
        } catch (SocketTimeoutException idle) {
          return; // fleet moved on
        }
        switch (e.type()) {
          case "ACK_RECEIVED" -> {
            acksReceived.incrementAndGet();
            ackLog.add(e.payload().path("deviceId").asText() + "|"
                + e.payload().path("alertId").asText() + "|" + e.messageId());
          }
          case "ACK_CANCEL" -> ackCancels.incrementAndGet();
          case "HEARTBEAT_ACK" -> heartbeatAcks.incrementAndGet();
          case "HEARTBEAT" -> {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("serverTime", Instant.now().toString());
            write(s, "HEARTBEAT_ACK", e.messageId(), p, "hba-" + UUID.randomUUID());
          }
          case "SYNC_REQUEST" -> {
            syncRequests.incrementAndGet();
            ObjectNode p = MAPPER.createObjectNode();
            p.putArray("alerts").add(alertPayload("ALT-SYNC-" + syncRequests.get(), 1,
                "DLV-SYNC-" + deviceId + "-" + syncRequests.get(), "SIMULATION — synced alert"));
            p.putArray("cancels");
            p.put("syncedAt", Instant.now().toString());
            write(s, "SYNC_RESULT", e.messageId(), p, "sync-" + UUID.randomUUID());
          }
          default -> { }
        }
      }
    } catch (EOFException | SocketException eof) {
      // agent disconnected (fault injection or shutdown) — normal
    } catch (Exception ignored) {
      // fixture errors surface as missing counts in assertions
    }
  }

  private String handshake(Socket s) throws Exception {
    Envelope hello = codec.readFrame(s.getInputStream());
    if (!"HELLO".equals(hello.type())) throw new IOException("expected HELLO, got " + hello.type());
    String deviceId = hello.payload().path("deviceId").asText();
    ObjectNode sh = MAPPER.createObjectNode();
    sh.put("serverId", "LOOPBACK-SIM");
    sh.putArray("supportedVersions").add(Protocol.VERSION);
    write(s, "SERVER_HELLO", hello.messageId(), sh, "sh-" + UUID.randomUUID());
    Envelope auth = codec.readFrame(s.getInputStream());
    if (!"AUTH_DEVICE".equals(auth.type())) throw new IOException("expected AUTH_DEVICE, got " + auth.type());
    ObjectNode ar = MAPPER.createObjectNode();
    ar.put("ok", authOk);
    if (!authOk) ar.put("reason", "bad secret");
    write(s, "AUTH_RESULT", auth.messageId(), ar, "ar-" + UUID.randomUUID());
    return authOk ? deviceId : null;
  }

  private void write(Socket s, String type, String correlationId, ObjectNode payload, String messageId)
      throws Exception {
    Envelope e = new Envelope(Protocol.VERSION, type, messageId, correlationId, Instant.now().toString(), payload);
    codec.writeFrame(s.getOutputStream(), e);
  }

  static ObjectNode alertPayload(String alertId, long revision, String deliveryId, String title) {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("incidentId", "INC-SIM");
    p.put("alertId", alertId);
    p.put("revision", revision);
    p.put("deliveryId", deliveryId);
    p.put("buildingId", "B01");
    p.put("category", "FIRE");
    p.put("priority", "CRITICAL");
    p.put("title", title);
    p.put("instruction", "Training only. Do not take real-world action.");
    p.put("simulation", true);
    p.put("urgency", "Immediate");
    p.put("severity", "Severe");
    p.put("certainty", "Observed");
    p.put("expiresAt", Instant.now().plusSeconds(3600).toString());
    return p;
  }

  @Override public void close() {
    try { serverSocket.close(); } catch (IOException ignored) { }
    exec.shutdownNow();
    try { exec.awaitTermination(5, TimeUnit.SECONDS); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }
}
