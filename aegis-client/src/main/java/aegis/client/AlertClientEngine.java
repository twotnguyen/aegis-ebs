package aegis.client;

import aegis.core.DedupIndex;
import aegis.core.RetryPolicy;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.FrameException;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * Headless connection engine for the AEGIS device client (SIMULATION/TRAINING).
 *
 * <p>Wire flow: HELLO -> SERVER_HELLO -> AUTH_DEVICE -> AUTH_RESULT, then
 * ALERT_PUSH / CANCEL_PUSH / SYNC_RESULT / HEARTBEAT dispatch. Guarantees:
 * <ul>
 *   <li>durable-first: inbox commit (WAL, synchronous=FULL) BEFORE ACK_RECEIVED;</li>
 *   <li>ACK_RECEIVED (transport) is distinct from ACK_USER (human gesture);</li>
 *   <li>dedup via {@link DedupIndex} seeded from the durable inbox — duplicates are
 *       re-ACKed (idempotent) but never redisplayed; cancel tombstones suppress stale pushes;</li>
 *   <li>reconnect with {@link RetryPolicy} exponential backoff + jitter, then SYNC_REQUEST
 *       with knownRevisions and re-send of durably-pending ACKs;</li>
 *   <li>heartbeat watchdog: silence of 3 x the negotiated heartbeat interval (advertised
 *       by the server in SERVER_HELLO/AUTH_RESULT, fallback cfg heartbeatTimeout) => suspect,
 *       reconnect;</li>
 *   <li>malformed-frame resilience: a RuntimeException while dispatching one frame (bad
 *       payload, local inbox failure) is reported via listenerError and the frame dropped —
 *       the controller keeps the connection and processes the next frame.</li>
 * </ul>
 * The JavaFX layer ({@link ClientApp}) is a thin listener over this engine; all logic
 * here is unit-testable over loopback with injected transport/sleeper/clock.
 */
public final class AlertClientEngine implements AutoCloseable {

  public enum State { NEW, CONNECTING, ONLINE, RECONNECTING, STOPPED, FAILED }

  /** Transport injection: TLS in production, plain loopback in logic tests. */
  public interface ConnectionFactory { Socket connect(String host, int port) throws IOException; }

  /** Backoff sleep injection (tests record delays instead of sleeping). */
  public interface Sleeper { void sleep(long ms) throws InterruptedException; }

  /** Permanent auth rejection — do not retry. */
  private static final class AuthRejectedException extends IOException {
    AuthRejectedException(String m) { super(m); }
  }

  private final ClientConfig cfg;
  private final InboxStore inbox;
  private final ConnectionFactory connections;
  private final Sleeper sleeper;
  private final Clock clock;
  private final RetryPolicy retry;
  private final DedupIndex dedup = new DedupIndex();
  private final ClientListener listener;
  private final FrameCodec codec = new FrameCodecImpl();
  private final ObjectMapper mapper = new ObjectMapper();

  private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean connectedOnce = new AtomicBoolean(false);
  private final AtomicLong lastFrameAt = new AtomicLong();
  private final AtomicInteger missedHeartbeats = new AtomicInteger();
  private final Object writeLock = new Object();
  private volatile Socket socket;
  private volatile OutputStream out;
  private volatile Thread controller;
  private volatile Thread heartbeatThread;
  private volatile long heartbeatIntervalMs;

  public AlertClientEngine(ClientConfig cfg, InboxStore inbox, ConnectionFactory connections,
                           Sleeper sleeper, Clock clock, RandomGenerator jitter, ClientListener listener) {
    this.cfg = cfg;
    this.inbox = inbox;
    this.connections = connections;
    this.sleeper = sleeper;
    this.clock = clock;
    this.retry = new RetryPolicy(cfg.retryMaxAttempts(), cfg.retryBaseDelayMs(), jitter);
    this.listener = listener;
    this.heartbeatIntervalMs = cfg.heartbeatTimeoutMs();
  }

  public State state() { return state.get(); }
  public boolean isOnline() { return state.get() == State.ONLINE; }
  public ClientConfig config() { return cfg; }

  /** Start the connect/read/reconnect controller on a virtual thread. */
  public void start() {
    if (!running.compareAndSet(false, true)) return;
    for (Map.Entry<String, Long> e : inbox.knownRevisions().entrySet()) {
      dedup.seen(e.getKey(), e.getValue(), cfg.deviceId()); // seed durable dedup state
    }
    Thread t = Thread.ofVirtual().name("aegis-client-controller").start(this::runLoop);
    controller = t;
    heartbeatThread = Thread.ofVirtual().name("aegis-client-heartbeat").start(this::heartbeatLoop);
  }

  /** Client-initiated HEARTBEAT every interval (server suspects after 3 missed). */
  private void heartbeatLoop() {
    while (running.get()) {
      try {
        Thread.sleep(heartbeatIntervalMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      }
      if (!running.get()) return;
      if (isOnline()) {
        try {
          ObjectNode p = mapper.createObjectNode();
          p.put("deviceId", cfg.deviceId());
          p.put("sentAt", now());
          send("HEARTBEAT", null, p);
        } catch (IOException | FrameException ex) {
          // link is down; the reader loop owns reconnect
        }
      }
    }
  }

  /** Stop gracefully; durably-pending ACKs survive in the inbox for the next session. */
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      setState(State.STOPPED);
      return;
    }
    closeSocket();
    Thread t = controller;
    if (t != null) {
      t.interrupt();
      try { t.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    Thread hb = heartbeatThread;
    if (hb != null) hb.interrupt();
    setState(State.STOPPED);
  }

  @Override public void close() { stop(); }

  /**
   * Human acknowledgement (UI button). Durable mark first; the ACK_USER frame is sent
   * when online, otherwise it stays pending and is re-sent after reconnect.
   * Returns false for unknown/cancelled/already-acknowledged alerts.
   */
  public boolean ackUser(String alertId, String userId) {
    var alert = inbox.find(alertId);
    if (alert.isEmpty()) return false;
    AlertRecord a = alert.get();
    String now = now();
    if (!inbox.markUserAck(alertId, a.revision(), userId, now)) return false;
    try {
      sendUserAck(a.alertId(), a.revision(), a.deliveryId(), a.messageId(), userId);
      inbox.markUserAckSent(alertId, a.revision(), now());
    } catch (IOException | FrameException ex) {
      listenerError("ACK_USER send (queued for reconnect)", ex); // durable mark stands
    }
    return true;
  }

  /** Manual SYNC_REQUEST (UI "Sync now"); no-op when offline. */
  public boolean requestSync() {
    if (!isOnline()) return false;
    try {
      sendSyncRequest();
      return true;
    } catch (IOException | FrameException ex) {
      listenerError("SYNC_REQUEST send", ex);
      return false;
    }
  }

  // ---- controller loop ----

  private void runLoop() {
    int attempts = 0;
    Throwable lastError = null;
    while (running.get()) {
      setState(attempts == 0 && !connectedOnce.get() ? State.CONNECTING : State.RECONNECTING);
      try {
        Socket s = connections.connect(cfg.host(), cfg.port());
        s.setTcpNoDelay(true);
        s.setSoTimeout((int) Math.min(cfg.heartbeatTimeoutMs(), Integer.MAX_VALUE));
        socket = s;
        out = s.getOutputStream();
        handshake(s);
        // L1: anchor the read timeout to the interval negotiated in SERVER_HELLO/AUTH_RESULT
        // (fallback cfg.heartbeatTimeoutMs when the server advertises nothing) — a healthy
        // server whose interval exceeds 3 x the local timeout must not cause false suspects.
        s.setSoTimeout((int) Math.min(heartbeatIntervalMs, Integer.MAX_VALUE));
        attempts = 0;
        missedHeartbeats.set(0);
        lastFrameAt.set(clock.millis());
        boolean reconnect = connectedOnce.getAndSet(true);
        setState(State.ONLINE);
        if (reconnect) sendSyncRequest();
        resendPendingAcks();
        readLoop(s);
      } catch (AuthRejectedException ex) {
        lastError = ex;
        listenerError("authentication rejected", ex);
        closeSocket();
        setState(State.FAILED);
        return;
      } catch (IOException | FrameException ex) {
        lastError = ex;
        if (running.get()) listenerError("connection", ex);
      } finally {
        closeSocket();
      }
      if (!running.get()) break;
      attempts++;
      if (!retry.shouldRetry(attempts)) {
        listenerError("retry exhausted after " + attempts + " attempts", lastError);
        setState(State.FAILED);
        return;
      }
      try {
        sleeper.sleep(retry.delayMs(attempts - 1));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    setState(State.STOPPED);
  }

  private void handshake(Socket s) throws IOException, FrameException {
    // Negotiate per connection: fall back to the local cfg until the server advertises.
    heartbeatIntervalMs = cfg.heartbeatTimeoutMs();
    InputStream in = s.getInputStream();
    ObjectNode hello = mapper.createObjectNode();
    hello.put("clientType", "DEVICE");
    hello.put("deviceId", cfg.deviceId());
    hello.putArray("supportedVersions").add(Protocol.VERSION);
    send("HELLO", null, hello);
    Envelope serverHello = expect(readFrame(in), "SERVER_HELLO");
    captureHeartbeatInterval(serverHello.payload());
    ObjectNode auth = mapper.createObjectNode();
    // aegis-server AuthService contract: deviceCode + credential (deviceId/secret kept
    // as aliases for older fixtures; extra fields are ignored by the server).
    auth.put("deviceCode", cfg.deviceId());
    auth.put("credential", cfg.deviceSecret());
    auth.put("deviceId", cfg.deviceId());
    auth.put("secret", cfg.deviceSecret());
    send("AUTH_DEVICE", null, auth);
    Envelope result = expect(readFrame(in), "AUTH_RESULT");
    captureHeartbeatInterval(result.payload());
    JsonNode ok = result.payload().get("ok");
    if (ok != null && ok.isBoolean() && !ok.booleanValue()) {
      String reason = result.payload().hasNonNull("reason") ? result.payload().get("reason").asText()
          : result.payload().path("code").asText("unspecified");
      throw new AuthRejectedException("AUTH_RESULT ok=false: " + reason);
    }
  }

  private void captureHeartbeatInterval(JsonNode payload) {
    JsonNode n = payload.get("heartbeatIntervalMs");
    if (n != null && n.canConvertToLong() && n.longValue() >= 100) {
      heartbeatIntervalMs = n.longValue();
    }
  }

  private void readLoop(Socket s) throws IOException, FrameException {
    InputStream in = s.getInputStream();
    while (running.get()) {
      Envelope e;
      try {
        e = codec.readFrame(in);
      } catch (SocketTimeoutException ste) {
        long silentMs = clock.millis() - lastFrameAt.get();
        missedHeartbeats.incrementAndGet();
        // L1: judge silence against the negotiated interval, not the local cfg timeout.
        if (silentMs >= 3 * heartbeatIntervalMs) {
          throw new IOException("heartbeat suspect: " + silentMs + "ms without frames ("
              + missedHeartbeats.get() + " missed)");
        }
        continue;
      }
      lastFrameAt.set(clock.millis());
      missedHeartbeats.set(0);
      try {
        dispatch(e);
      } catch (RuntimeException ex) {
        // M1: a malformed frame (IllegalArgumentException from AlertRecord) or a local
        // inbox failure (IllegalStateException from SQLite) must not kill the controller.
        // Drop this frame, report it, keep the connection and process the next frame.
        listenerError("malformed frame " + e.type(), ex);
      }
    }
  }

  private void dispatch(Envelope e) throws IOException, FrameException {
    switch (e.type()) {
      case "ALERT_PUSH" -> handleAlert(AlertRecord.fromPush(e, now()));
      case "CANCEL_PUSH" -> handleCancel(e.payload(), e.messageId());
      case "SYNC_RESULT" -> {
        JsonNode alerts = e.payload().get("alerts");
        if (alerts != null && alerts.isArray()) {
          for (JsonNode a : alerts) handleAlert(AlertRecord.fromPayload(a, e.messageId(), e.correlationId(), e.sentAt(), now()));
        }
        // aegis-server SyncService sends "tombstones"; fixtures/lab servers may send "cancels".
        for (String field : new String[]{"cancels", "tombstones"}) {
          JsonNode cancels = e.payload().get(field);
          if (cancels != null && cancels.isArray()) {
            for (JsonNode c : cancels) handleCancel(c, e.messageId());
          }
        }
      }
      case "HEARTBEAT" -> {
        ObjectNode p = mapper.createObjectNode();
        p.put("deviceId", cfg.deviceId());
        p.put("receivedAt", now());
        send("HEARTBEAT_ACK", e.messageId(), p);
      }
      case "ERROR" -> listenerError("server ERROR frame", new IOException(String.valueOf(e.payload())));
      default -> { /* SERVER_HELLO/AUTH_RESULT outside handshake, STATUS_* etc: ignore */ }
    }
  }

  /** Durable-first alert handling; DedupIndex gates display, inbox is the durable truth. */
  private void handleAlert(AlertRecord a) throws IOException, FrameException {
    boolean duplicate = dedup.seen(a.alertId(), a.revision(), cfg.deviceId());
    if (duplicate) {
      // Already durably stored (index seeded from inbox): re-ACK so the server stops
      // retrying, but never redisplay. ACK only advances state — idempotent server-side.
      sendAckReceived(a.alertId(), a.revision(), a.deliveryId(), a.messageId());
      fireAlert(a, false);
      return;
    }
    InboxStore.StoreResult r = inbox.store(a); // COMMIT before any ACK goes on the wire
    if (r == InboxStore.StoreResult.STORED) {
      fireAlert(a, true);
      sendAckReceived(a.alertId(), a.revision(), a.deliveryId(), a.messageId());
      inbox.markAckReceivedSent(a.alertId(), a.revision(), now());
    } else {
      // Inbox outranks the in-memory index (restart/race edge): durable row exists.
      sendAckReceived(a.alertId(), a.revision(), a.deliveryId(), a.messageId());
      fireAlert(a, false);
    }
  }

  private void handleCancel(JsonNode p, String pushMessageId) throws IOException, FrameException {
    String alertId = p.path("alertId").asText(null);
    long revision = p.path("revision").asLong(0);
    if (alertId == null || alertId.isBlank() || revision < 1) {
      listenerError("malformed CANCEL_PUSH", new IOException(String.valueOf(p)));
      return;
    }
    String reason = p.hasNonNull("reason") ? p.get("reason").asText() : null;
    // Look up the stored delivery BEFORE the tombstone hides the row (find() skips cancelled).
    String deliveryId = p.hasNonNull("deliveryId") ? p.get("deliveryId").asText()
        : inbox.find(alertId).map(AlertRecord::deliveryId).orElse(null);
    dedup.seen(alertId, revision, cfg.deviceId()); // tombstone revision suppresses stale pushes
    boolean applied = inbox.applyCancel(alertId, revision, cfg.deviceId(), reason, now());
    ObjectNode ack = mapper.createObjectNode();
    ack.put("alertId", alertId);
    ack.put("revision", revision);
    ack.put("deviceId", cfg.deviceId());
    ack.put("ackAt", now());
    // aegis-server ack() matches ACK_CANCEL against a delivery row: echo the push's
    // deliveryId when present, else the durably-stored delivery for this alert.
    if (deliveryId != null) ack.put("deliveryId", deliveryId);
    if (p.hasNonNull("operationId")) ack.put("operationId", p.get("operationId").asText());
    send("ACK_CANCEL", pushMessageId, ack);
    if (applied) {
      try { listener.onCancel(alertId, revision, reason); }
      catch (RuntimeException ex) { listenerError("listener.onCancel", ex); }
    }
  }

  // ---- outbound frames ----

  private void sendAckReceived(String alertId, long revision, String deliveryId, String correlationId)
      throws IOException, FrameException {
    ObjectNode p = mapper.createObjectNode();
    p.put("alertId", alertId);
    p.put("deliveryId", deliveryId);
    p.put("revision", revision);
    p.put("deviceId", cfg.deviceId());
    p.put("ackAt", now());
    send("ACK_RECEIVED", correlationId, p);
  }

  private void sendUserAck(String alertId, long revision, String deliveryId, String correlationId, String userId)
      throws IOException, FrameException {
    ObjectNode p = mapper.createObjectNode();
    p.put("alertId", alertId);
    p.put("deliveryId", deliveryId);
    p.put("revision", revision);
    p.put("deviceId", cfg.deviceId());
    p.put("userId", userId);
    p.put("action", "ACKNOWLEDGE");
    send("ACK_USER", correlationId, p);
  }

  private void sendSyncRequest() throws IOException, FrameException {
    ObjectNode p = mapper.createObjectNode();
    p.put("deviceId", cfg.deviceId());
    ObjectNode known = p.putObject("knownRevisions");
    inbox.knownRevisions().forEach(known::put);
    send("SYNC_REQUEST", null, p);
  }

  /** Re-send durably-pending ACKs (crash between commit and send, or offline user gesture). */
  private void resendPendingAcks() throws IOException, FrameException {
    for (InboxStore.PendingAck pending : inbox.pendingReceivedAcks()) {
      sendAckReceived(pending.alertId(), pending.revision(), pending.deliveryId(), pending.messageId());
      inbox.markAckReceivedSent(pending.alertId(), pending.revision(), now());
    }
    for (InboxStore.PendingAck pending : inbox.pendingUserAcks()) {
      sendUserAck(pending.alertId(), pending.revision(), pending.deliveryId(), pending.messageId(), pending.userId());
      inbox.markUserAckSent(pending.alertId(), pending.revision(), now());
    }
  }

  private void send(String type, String correlationId, JsonNode payload) throws IOException, FrameException {
    Envelope e = new Envelope(Protocol.VERSION, type, "msg-" + UUID.randomUUID(), correlationId, now(), payload);
    synchronized (writeLock) {
      OutputStream o = out;
      Socket s = socket;
      if (o == null || s == null || s.isClosed() || !s.isConnected()) throw new IOException("offline: no connection");
      codec.writeFrame(o, e);
    }
  }

  // ---- helpers ----

  private Envelope readFrame(InputStream in) throws IOException, FrameException {
    return codec.readFrame(in);
  }

  private static Envelope expect(Envelope e, String type) throws IOException {
    if (!type.equals(e.type())) throw new IOException("expected " + type + " but got " + e.type());
    return e;
  }

  private void closeSocket() {
    Socket s = socket;
    socket = null;
    out = null;
    if (s != null) {
      try { s.close(); } catch (IOException ignored) { }
    }
  }

  private void setState(State next) {
    State prev = state.getAndSet(next);
    if (prev != next) {
      try { listener.onState(prev, next); }
      catch (RuntimeException ex) { listenerError("listener.onState", ex); }
    }
  }

  private void fireAlert(AlertRecord a, boolean fresh) {
    try { listener.onAlert(a, fresh); }
    catch (RuntimeException ex) { listenerError("listener.onAlert", ex); }
  }

  private void listenerError(String context, Throwable t) {
    try { listener.onError(context, t); }
    catch (RuntimeException ignored) { }
  }

  private String now() { return Instant.now(clock).toString(); }
}
