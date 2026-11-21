package aegis.server;

import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loopback EBSP/2.0 TLS client for integration tests. AUTO mode runs a reader
 * thread demuxing frames into per-type queues (and answering server HEARTBEATs
 * when autoHeartbeat is set); MANUAL mode reads synchronously (used by the
 * slow-client test, which must NOT drain its socket).
 */
final class TestTlsClient implements AutoCloseable {

  private final SSLSocket socket;
  private final FrameCodec codec = new FrameCodecImpl();
  private final Map<String, BlockingQueue<Envelope>> queues = new ConcurrentHashMap<>();
  // fair lock: a flooding main thread must not starve the reader's HEARTBEAT_ACK replies
  private final java.util.concurrent.locks.ReentrantLock writeLock =
      new java.util.concurrent.locks.ReentrantLock(true);
  private final boolean auto;
  private volatile boolean autoHeartbeat;
  private volatile boolean closed = false;
  private volatile String closeReason;
  private Thread reader;

  TestTlsClient(int port, boolean auto, boolean autoHeartbeat) throws Exception {
    this.auto = auto;
    this.autoHeartbeat = autoHeartbeat;
    this.socket = (SSLSocket) TestSupport.trustAllContext().getSocketFactory()
        .createSocket("localhost", port);
    this.socket.setUseClientMode(true);
    this.socket.setTcpNoDelay(true);
    this.socket.startHandshake();
    if (auto) {
      reader = new Thread(this::readLoop, "test-tls-reader");
      reader.setDaemon(true);
      reader.start();
    }
  }

  private void readLoop() {
    try {
      while (!closed) {
        Envelope env = codec.readFrame(socket.getInputStream());
        if (env.type().equals("HEARTBEAT") && autoHeartbeat) {
          send("HEARTBEAT_ACK", Json.obj().put("clientTime", Json.ts(java.time.Instant.now())),
              env.messageId());
          continue;
        }
        queue(env.type()).offer(env);
      }
    } catch (Exception e) {
      closeReason = e.toString();
      closed = true;
    }
  }

  private BlockingQueue<Envelope> queue(String type) {
    return queues.computeIfAbsent(type, k -> new LinkedBlockingQueue<>());
  }

  void setAutoHeartbeat(boolean value) { this.autoHeartbeat = value; }

  void send(String type, JsonNode payload, String correlationId) {
    sendEnvelope(new Envelope(Protocol.VERSION, type, Json.uuid(), correlationId,
        Json.ts(java.time.Instant.now()), payload));
  }

  private void sendEnvelope(Envelope env) {
    try {
      writeLock.lock();
      try {
        codec.writeFrame(socket.getOutputStream(), env);
      } finally {
        writeLock.unlock();
      }
    } catch (Exception e) {
      closed = true;
      throw new RuntimeException("send failed: " + env.type(), e);
    }
  }

  /**
   * Sends a command and waits for the protocol reply CORRELATED to this request's
   * messageId (the server echoes it as correlationId). Stale replies of the same
   * type left over from earlier commands are never mistaken for this reply.
   */
  Envelope request(String type, JsonNode payload, long timeoutMs) {
    String messageId = Json.uuid();
    sendEnvelope(new Envelope(Protocol.VERSION, type, messageId, null,
        Json.ts(java.time.Instant.now()), payload));
    return awaitReply(replyType(type), messageId, timeoutMs);
  }

  private static String replyType(String type) {
    return switch (type) {
      case "HELLO" -> "SERVER_HELLO";
      case "AUTH_OPERATOR", "AUTH_DEVICE" -> "AUTH_RESULT";
      case "SYNC_REQUEST" -> "SYNC_RESULT";
      case "HEARTBEAT" -> "HEARTBEAT_ACK";
      case "STATUS_SUBSCRIBE" -> "STATUS_SNAPSHOT";
      default -> "COMMAND_RESULT";
    };
  }

  /** Like {@link #await} but only accepts frames correlated to {@code correlationId}. */
  Envelope awaitReply(String type, String correlationId, long timeoutMs) {
    if (auto) {
      List<Envelope> unmatched = new ArrayList<>();
      try {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
          Envelope env = queue(type).poll(50, TimeUnit.MILLISECONDS);
          if (env != null) {
            if (correlationId.equals(env.correlationId())) {
              queue(type).addAll(unmatched); // keep stale frames for later drains
              return env;
            }
            unmatched.add(env);
            continue;
          }
          if (closed) fail("connection closed while awaiting " + type + ": " + closeReason);
        }
        queue(type).addAll(unmatched);
        fail("timed out awaiting " + type + " correlated to " + correlationId);
        return null;
      } catch (InterruptedException e) {
        queue(type).addAll(unmatched);
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    // manual mode: read synchronously, stashing other types / uncorrelated frames
    try {
      socket.setSoTimeout((int) Math.min(timeoutMs, 5000));
      long end = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < end) {
        Envelope env = codec.readFrame(socket.getInputStream());
        if (env.type().equals(type) && correlationId.equals(env.correlationId())) return env;
        queue(env.type()).offer(env);
      }
      fail("timed out awaiting " + type + " correlated to " + correlationId);
      return null;
    } catch (IOException | aegis.protocol.FrameException e) {
      closed = true;
      fail("connection closed while awaiting " + type + ": " + e);
      return null;
    }
  }

  Envelope await(String type, long timeoutMs) {
    if (auto) {
      try {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
          Envelope env = queue(type).poll(50, TimeUnit.MILLISECONDS);
          if (env != null) return env;
          if (closed) fail("connection closed while awaiting " + type + ": " + closeReason);
        }
        fail("timed out awaiting " + type);
        return null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    // manual mode: read synchronously, stashing other types
    try {
      socket.setSoTimeout((int) Math.min(timeoutMs, 5000));
      long end = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < end) {
        Envelope env = codec.readFrame(socket.getInputStream());
        if (env.type().equals(type)) return env;
        queue(env.type()).offer(env);
      }
      fail("timed out awaiting " + type);
      return null;
    } catch (IOException | aegis.protocol.FrameException e) {
      closed = true;
      fail("connection closed while awaiting " + type + ": " + e);
      return null;
    }
  }

  List<Envelope> drain(String type) {
    List<Envelope> out = new ArrayList<>();
    queue(type).drainTo(out);
    return out;
  }

  int queued(String type) { return queue(type).size(); }

  boolean isClosed() { return closed || socket.isClosed(); }

  void awaitClosed(long timeoutMs) {
    TestSupport.until("client connection closed", this::isClosed, timeoutMs);
  }

  SSLSocket socket() { return socket; }

  ObjectNode helloPayload() {
    return Json.obj().put("clientType", "DEVICE").put("deviceId", "test");
  }

  @Override
  public void close() {
    closed = true;
    try {
      socket.close();
    } catch (IOException ignored) {
      // already gone
    }
    if (reader != null) reader.interrupt();
  }
}
