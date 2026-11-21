package aegis.server;

import aegis.core.DeviceLocation;
import aegis.core.Grants;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.Priority;
import aegis.protocol.Role;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One authenticated (or pending) TLS client connection. All server-&gt;client
 * frames flow through a single bounded writer queue + one writer thread, so
 * frames never interleave and backpressure is observable (slow-client policy).
 */
public final class ClientSession {

  public enum Kind { UNAUTHENTICATED, OPERATOR, DEVICE }

  private final String sessionId;
  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final FrameCodec codec;
  private final BoundedWriterQueue queue;
  private final Clock clock;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Consumer<ClientSession> onClose;
  private final Consumer<ClientSession> onSlowClient;

  private volatile Kind kind = Kind.UNAUTHENTICATED;
  private volatile String userId;
  private volatile Role role;
  private volatile Grants grants = Grants.none();
  private volatile String deviceId;
  private volatile DeviceLocation location;
  private volatile int floorNumber;
  private volatile String zoneCode;
  private volatile Instant lastHeartbeatAt;
  private volatile boolean suspect = false;
  private final Instant createdAt;

  public ClientSession(String sessionId, Socket socket, FrameCodec codec, int queueCapacity,
                       Clock clock, Executor writerExecutor,
                       Consumer<ClientSession> onClose, Consumer<ClientSession> onSlowClient)
      throws IOException {
    this.sessionId = sessionId;
    this.socket = socket;
    this.in = socket.getInputStream();
    this.out = socket.getOutputStream();
    this.codec = codec;
    this.queue = new BoundedWriterQueue(queueCapacity);
    this.clock = clock;
    this.onClose = onClose;
    this.onSlowClient = onSlowClient;
    this.createdAt = clock.instant();
    this.lastHeartbeatAt = createdAt;
    writerExecutor.execute(this::writerLoop);
  }

  /** Enqueues a frame; applies the slow-client policy on overflow. */
  public BoundedWriterQueue.OfferResult send(Priority priority, Envelope envelope) {
    if (closed.get()) return BoundedWriterQueue.OfferResult.REJECTED_SLOW;
    BoundedWriterQueue.OfferResult result = queue.offer(priority, envelope);
    if (result == BoundedWriterQueue.OfferResult.REJECTED_SLOW) {
      onSlowClient.accept(this);
    }
    return result;
  }

  private void writerLoop() {
    try {
      while (!closed.get()) {
        Envelope next = queue.take();
        if (next == null) return; // queue closed
        codec.writeFrame(out, next);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | aegis.protocol.FrameException | RuntimeException e) {
      // broken pipe / codec failure: drop the connection
    } finally {
      close();
    }
  }

  public void bindOperator(String userId, Role role, Grants grants) {
    this.userId = userId;
    this.role = role;
    this.grants = grants == null ? Grants.none() : grants;
    this.kind = Kind.OPERATOR;
    touchHeartbeat();
  }

  public void bindDevice(String deviceId, DeviceLocation location, int floorNumber, String zoneCode) {
    this.deviceId = deviceId;
    this.location = location;
    this.floorNumber = floorNumber;
    this.zoneCode = zoneCode;
    this.kind = Kind.DEVICE;
    touchHeartbeat();
  }

  public void touchHeartbeat() { this.lastHeartbeatAt = clock.instant(); }

  public void markSuspect() { this.suspect = true; }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    queue.close();
    try {
      socket.close();
    } catch (IOException ignored) {
      // already broken
    }
    onClose.accept(this);
  }

  public String sessionId() { return sessionId; }
  public Kind kind() { return kind; }
  public boolean isAuthenticated() { return kind != Kind.UNAUTHENTICATED; }
  public String userId() { return userId; }
  public Role role() { return role; }
  public Grants grants() { return grants; }
  public String deviceId() { return deviceId; }
  public DeviceLocation location() { return location; }
  public int floorNumber() { return floorNumber; }
  public String zoneCode() { return zoneCode; }
  public Instant lastHeartbeatAt() { return lastHeartbeatAt; }
  /** Connection establishment time — basis for the absolute pre-auth TTL (S-07). */
  public Instant createdAt() { return createdAt; }
  public boolean isSuspect() { return suspect; }
  public boolean isClosed() { return closed.get(); }
  public InputStream input() { return in; }
  public BoundedWriterQueue writerQueue() { return queue; }
  public Socket socket() { return socket; }
}
