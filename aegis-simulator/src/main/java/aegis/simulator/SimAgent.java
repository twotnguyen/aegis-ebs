package aegis.simulator;

import aegis.core.DedupIndex;
import aegis.core.RetryPolicy;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.FrameException;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One headless simulated device (SIMULATION/TRAINING). TLS-connects to the server,
 * performs HELLO/AUTH_DEVICE, receives ALERT_PUSH/CANCEL_PUSH/SYNC_RESULT/HEARTBEAT,
 * and answers ACK_RECEIVED / ACK_CANCEL / HEARTBEAT_ACK subject to seed-reproducible
 * fault injection (drop ACK, delay ACK, duplicate ACK, disconnect + reconnect + SYNC).
 *
 * <p>No JavaFX, no local persistence: dedup is {@link DedupIndex} in memory and
 * knownRevisions for SYNC_REQUEST are tracked per agent. One virtual thread per agent.
 * AUTH_RESULT ok=false is permanent: the agent fails fast (counted in
 * {@link SimStats#authRejections}) instead of retrying until the deadline.
 */
public final class SimAgent implements Runnable {

  /** Fault-injected disconnect — expected, not an error. */
  private static final class FaultDisconnectException extends IOException {
    FaultDisconnectException() { super("fault injection: disconnect after delivery"); }
  }

  /** Permanent auth rejection (AUTH_RESULT ok=false) — stop the agent, do not retry. */
  private static final class AuthRejectedException extends IOException {
    AuthRejectedException(String m) { super(m); }
  }

  private final String deviceId;
  private volatile String wireDeviceId;
  private final SimulatorConfig cfg;
  private final SSLContext tls;
  private final FaultInjector faults;
  private final SimStats stats;
  private final long deadlineMillis;
  private final Clock clock;
  private final DedupIndex dedup = new DedupIndex();
  private final Map<String, Long> knownRevisions = new ConcurrentHashMap<>();
  private final Map<String, String> deliveryIds = new ConcurrentHashMap<>();
  private final FrameCodec codec = new FrameCodecImpl();
  private final ObjectMapper mapper = new ObjectMapper();
  private final RetryPolicy retry = new RetryPolicy(10_000, 200); // keep retrying until deadline
  private boolean connectedOnce;
  private volatile long heartbeatIntervalMs = 10_000;
  private long lastHeartbeatSent;

  public SimAgent(String deviceId, SimulatorConfig cfg, SSLContext tls, FaultInjector faults,
                  SimStats stats, long deadlineMillis, Clock clock) {
    this.deviceId = deviceId;
    this.cfg = cfg;
    this.tls = tls;
    this.faults = faults;
    this.stats = stats;
    this.deadlineMillis = deadlineMillis;
    this.clock = clock;
  }

  public String deviceId() { return deviceId; }

  @Override
  public void run() {
    int attempts = 0;
    while (clock.millis() < deadlineMillis && !Thread.currentThread().isInterrupted()) {
      boolean handshakeDone = false;
      try (Socket s = tls.getSocketFactory().createSocket(cfg.host(), cfg.port())) {
        s.setTcpNoDelay(true);
        s.setSoTimeout(2000); // wake up regularly to check the deadline
        handshake(s);
        handshakeDone = true;
        stats.connectSuccess.increment();
        attempts = 0;
        if (connectedOnce) {
          stats.reconnects.increment();
          sendSyncRequest(s);
          stats.syncRequests.increment();
        }
        connectedOnce = true;
        readLoop(s);
      } catch (AuthRejectedException permanent) {
        // L4: ok=false is permanent (bad credentials) — fail fast like AlertClientEngine
        // instead of hammering auth until the deadline and inflating connectFailures.
        stats.authRejections.increment();
        break;
      } catch (FaultDisconnectException expected) {
        // injected disconnect: fall through to reconnect
      } catch (IOException | FrameException ex) {
        if (handshakeDone) stats.connectionErrors.increment();
        else stats.connectFailures.increment();
      }
      if (clock.millis() >= deadlineMillis) break;
      attempts++;
      long remaining = deadlineMillis - clock.millis();
      long delay = Math.min(retry.delayMs(Math.min(attempts - 1, 12)), Math.max(remaining, 0));
      if (delay > 0) {
        try { Thread.sleep(delay); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
      }
    }
    stats.agentsFinished.increment();
  }

  // ---- protocol ----

  private void handshake(Socket s) throws IOException, FrameException {
    InputStream in = s.getInputStream();
    ObjectNode hello = mapper.createObjectNode();
    hello.put("clientType", "DEVICE");
    hello.put("deviceId", deviceId);
    hello.putArray("supportedVersions").add(Protocol.VERSION);
    send(s, "HELLO", null, hello);
    Envelope serverHello = expect(codec.readFrame(in), "SERVER_HELLO");
    JsonNode interval = serverHello.payload().get("heartbeatIntervalMs");
    if (interval != null && interval.canConvertToLong() && interval.longValue() >= 100) {
      heartbeatIntervalMs = interval.longValue();
    }
    ObjectNode auth = mapper.createObjectNode();
    // aegis-server AuthService contract: deviceCode + credential.
    auth.put("deviceCode", deviceId);
    auth.put("credential", cfg.deviceSecret());
    auth.put("deviceId", deviceId);
    send(s, "AUTH_DEVICE", null, auth);
    Envelope result = expect(codec.readFrame(in), "AUTH_RESULT");
    JsonNode ok = result.payload().get("ok");
    if (ok != null && ok.isBoolean() && !ok.booleanValue()) {
      throw new AuthRejectedException("AUTH_RESULT ok=false for " + deviceId);
    }
    lastHeartbeatSent = 0; // heartbeat promptly after (re)connect
    wireDeviceId = result.payload().path("deviceId").asText(deviceId);
  }

  private String wireDeviceId() {
    return wireDeviceId == null ? deviceId : wireDeviceId;
  }

  private void readLoop(Socket s) throws IOException, FrameException {
    InputStream in = s.getInputStream();
    while (clock.millis() < deadlineMillis && !Thread.currentThread().isInterrupted()) {
      Envelope e;
      try {
        e = codec.readFrame(in);
      } catch (SocketTimeoutException ste) {
        maybeHeartbeat(s); // soTimeout tick: keep the server's heartbeat accounting fresh
        continue;
      }
      switch (e.type()) {
        case "ALERT_PUSH" -> handleAlert(s, e.payload(), e.messageId());
        case "SYNC_RESULT" -> {
          JsonNode alerts = e.payload().get("alerts");
          if (alerts != null && alerts.isArray()) {
            for (JsonNode a : alerts) handleAlert(s, a, e.messageId());
          }
          // aegis-server SyncService sends "tombstones"; fixtures may send "cancels".
          for (String field : new String[]{"cancels", "tombstones"}) {
            JsonNode cancels = e.payload().get(field);
            if (cancels != null && cancels.isArray()) {
              for (JsonNode c : cancels) handleCancel(s, c, e.messageId());
            }
          }
        }
        case "CANCEL_PUSH" -> handleCancel(s, e.payload(), e.messageId());
        case "HEARTBEAT" -> {
          ObjectNode p = mapper.createObjectNode();
          p.put("deviceId", wireDeviceId());
          p.put("receivedAt", now());
          send(s, "HEARTBEAT_ACK", e.messageId(), p);
          stats.heartbeats.increment();
        }
        default -> { /* SERVER_HELLO/AUTH_RESULT outside handshake, ERROR, ...: ignore */ }
      }
    }
  }

  private void handleAlert(Socket s, JsonNode payload, String pushMessageId)
      throws IOException, FrameException {
    String alertId = payload.path("alertId").asText(null);
    long revision = payload.path("revision").asLong(0);
    String deliveryId = payload.path("deliveryId").asText("");
    if (alertId == null || revision < 1) return; // malformed push: ignore
    stats.alertsReceived.increment();
    if (dedup.seen(alertId, revision, deviceId)) stats.duplicatePushes.increment();
    knownRevisions.merge(alertId, revision, Math::max);
    if (knownRevisions.get(alertId) == revision) deliveryIds.put(alertId, deliveryId);

    FaultDecision d = faults.decide();
    if (d.dropAck()) {
      stats.acksDropped.increment(); // server must retry / recover via SYNC
    } else {
      if (d.delayMs() > 0) {
        stats.acksDelayed.increment();
        long sleep = Math.min(d.delayMs(), Math.max(0, deadlineMillis - clock.millis()));
        if (sleep > 0) {
          try { Thread.sleep(sleep); }
          catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
      }
      for (int k = 0; k < d.ackCopies(); k++) {
        sendAckReceived(s, alertId, revision, deliveryId, pushMessageId); // fresh messageId per copy
        stats.acksSent.increment();
        if (k > 0) stats.duplicateAcks.increment();
      }
    }
    if (d.disconnectAfter()) {
      stats.disconnects.increment();
      throw new FaultDisconnectException();
    }
  }

  private void handleCancel(Socket s, JsonNode payload, String pushMessageId)
      throws IOException, FrameException {
    String alertId = payload.path("alertId").asText(null);
    long revision = payload.path("revision").asLong(0);
    if (alertId == null || revision < 1) return;
    stats.cancelsReceived.increment();
    dedup.seen(alertId, revision, deviceId);
    knownRevisions.merge(alertId, revision, Math::max);
    ObjectNode ack = mapper.createObjectNode();
    ack.put("alertId", alertId);
    ack.put("revision", revision);
    ack.put("deviceId", wireDeviceId());
    ack.put("ackAt", now());
    String deliveryId = payload.hasNonNull("deliveryId") ? payload.get("deliveryId").asText()
        : deliveryIds.get(alertId);
    if (deliveryId != null) ack.put("deliveryId", deliveryId);
    if (payload.hasNonNull("operationId")) ack.put("operationId", payload.get("operationId").asText());
    send(s, "ACK_CANCEL", pushMessageId, ack);
  }

  /** Client-initiated HEARTBEAT at the server-advertised interval (default 10s). */
  private void maybeHeartbeat(Socket s) throws IOException, FrameException {
    long now = clock.millis();
    if (now - lastHeartbeatSent >= heartbeatIntervalMs) {
      ObjectNode p = mapper.createObjectNode();
      p.put("deviceId", wireDeviceId());
      p.put("sentAt", now());
      send(s, "HEARTBEAT", null, p);
      lastHeartbeatSent = now;
    }
  }

  private void sendAckReceived(Socket s, String alertId, long revision, String deliveryId,
                               String correlationId) throws IOException, FrameException {
    ObjectNode p = mapper.createObjectNode();
    p.put("alertId", alertId);
    p.put("deliveryId", deliveryId);
    p.put("revision", revision);
    p.put("deviceId", wireDeviceId());
    p.put("ackAt", now());
    send(s, "ACK_RECEIVED", correlationId, p);
  }

  private void sendSyncRequest(Socket s) throws IOException, FrameException {
    ObjectNode p = mapper.createObjectNode();
    p.put("deviceId", wireDeviceId());
    ObjectNode known = p.putObject("knownRevisions");
    knownRevisions.forEach(known::put);
    send(s, "SYNC_REQUEST", null, p);
  }

  private void send(Socket s, String type, String correlationId, JsonNode payload)
      throws IOException, FrameException {
    Envelope e = new Envelope(Protocol.VERSION, type, "sim-" + UUID.randomUUID(), correlationId, now(), payload);
    OutputStream out = s.getOutputStream();
    synchronized (out) {
      codec.writeFrame(out, e);
    }
  }

  private static Envelope expect(Envelope e, String type) throws IOException {
    if (!type.equals(e.type())) throw new IOException("expected " + type + " but got " + e.type());
    return e;
  }

  private String now() { return Instant.now(clock).toString(); }
}
