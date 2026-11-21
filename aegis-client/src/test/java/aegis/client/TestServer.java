package aegis.client;

import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Scripted EBSP/2.0 loopback test server (plain TCP or TLS). Records every post-handshake
 * frame; scripts drive pushes/closes/sync per connection index. Test scope only.
 */
final class TestServer implements AutoCloseable {

  interface Script { void run(int connIndex, Session s) throws Exception; }

  static final ObjectMapper MAPPER = new ObjectMapper();
  private final FrameCodec codec = new FrameCodecImpl();
  final List<Envelope> frames = new CopyOnWriteArrayList<>();
  final AtomicInteger connections = new AtomicInteger();
  volatile Consumer<Envelope> onFrame = e -> {};
  volatile boolean sawTlsSocket;

  private final ServerSocket serverSocket;
  private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
  private final Script script;

  TestServer(Script script) throws IOException { this(script, null); }

  TestServer(Script script, SSLContext serverTls) throws IOException {
    this.script = script;
    this.serverSocket = serverTls == null
        ? new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        : serverTls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    exec.submit(() -> {
      while (!serverSocket.isClosed()) {
        try {
          Socket s = serverSocket.accept();
          int idx = connections.getAndIncrement();
          exec.submit(() -> {
            Session session = new Session(s);
            try {
              script.run(idx, session);
            } catch (EOFException | SocketException eof) {
              // client went away — normal in fault tests
            } catch (Exception ex) {
              // script failure surfaces through missing frames / test timeouts
            } finally {
              session.close();
            }
          });
        } catch (IOException closed) {
          return;
        }
      }
    });
  }

  int port() { return serverSocket.getLocalPort(); }

  List<Envelope> framesOfType(String type) {
    return frames.stream().filter(e -> e.type().equals(type)).toList();
  }

  @Override public void close() {
    try { serverSocket.close(); } catch (IOException ignored) { }
    exec.shutdownNow();
  }

  /** One connection: framed read/write + handshake helper. */
  final class Session {
    final Socket socket;
    String deviceId;

    Session(Socket socket) { this.socket = socket; }

    Envelope read() throws Exception { return codec.readFrame(socket.getInputStream()); }

    void write(Envelope e) throws Exception { codec.writeFrame(socket.getOutputStream(), e); }

    void write(String type, String correlationId, JsonNode payload) throws Exception {
      write(env(type, "srv-" + UUID.randomUUID(), correlationId, payload));
    }

    /** HELLO -> SERVER_HELLO -> AUTH_DEVICE -> AUTH_RESULT. */
    void handshake(boolean authOk) throws Exception {
      if (socket instanceof javax.net.ssl.SSLSocket) sawTlsSocket = true;
      Envelope hello = read();
      if (!"HELLO".equals(hello.type())) throw new IOException("expected HELLO, got " + hello.type());
      deviceId = hello.payload().path("deviceId").asText();
      ObjectNode sh = MAPPER.createObjectNode();
      sh.put("serverId", "LOOPBACK-TEST");
      sh.putArray("supportedVersions").add(Protocol.VERSION);
      write("SERVER_HELLO", hello.messageId(), sh);
      Envelope auth = read();
      if (!"AUTH_DEVICE".equals(auth.type())) throw new IOException("expected AUTH_DEVICE, got " + auth.type());
      ObjectNode ar = MAPPER.createObjectNode();
      ar.put("ok", authOk);
      if (!authOk) ar.put("reason", "bad secret");
      write("AUTH_RESULT", auth.messageId(), ar);
    }

    void pushAlert(JsonNode alertPayload, String messageId) throws Exception {
      write("ALERT_PUSH", "req-sim", alertPayload, messageId);
    }

    void write(String type, String correlationId, JsonNode payload, String messageId) throws Exception {
      write(new Envelope(Protocol.VERSION, type, messageId, correlationId, Instant.now().toString(), payload));
    }

    /** Record frames; auto-answer SYNC_REQUEST (with syncAlerts) and HEARTBEAT. */
    void recordLoop(List<JsonNode> syncAlerts, boolean closeAfterFirstAck) throws Exception {
      while (!socket.isClosed()) {
        Envelope e = read();
        frames.add(e);
        onFrame.accept(e);
        switch (e.type()) {
          case "ACK_RECEIVED" -> { if (closeAfterFirstAck) { close(); return; } }
          case "SYNC_REQUEST" -> {
            ObjectNode p = MAPPER.createObjectNode();
            var arr = p.putArray("alerts");
            syncAlerts.forEach(arr::add);
            p.putArray("cancels");
            p.put("syncedAt", Instant.now().toString());
            write("SYNC_RESULT", e.messageId(), p);
          }
          case "HEARTBEAT" -> {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("serverTime", Instant.now().toString());
            write("HEARTBEAT_ACK", e.messageId(), p);
          }
          default -> { }
        }
      }
    }

    void close() {
      try { socket.close(); } catch (IOException ignored) { }
    }

    /** RST close: guarantees the peer's next write/read fails (lost-ACK simulation). */
    void rstClose() {
      try { socket.setSoLinger(true, 0); } catch (SocketException ignored) { }
      close();
    }
  }

  static Envelope env(String type, String messageId, String correlationId, JsonNode payload) {
    return new Envelope(Protocol.VERSION, type, messageId, correlationId, Instant.now().toString(), payload);
  }

  static ObjectNode alertPayload(String alertId, long revision, String deliveryId, String title) {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("incidentId", "INC-TEST-" + alertId);
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

  static ObjectNode cancelPayload(String alertId, long revision, String reason) {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("alertId", alertId);
    p.put("revision", revision);
    p.put("operationId", "OP-" + UUID.randomUUID());
    p.put("reason", reason);
    p.put("simulation", true);
    return p;
  }

  /** Handshake, push alerts, then record loop (optionally close right after pushing). */
  static Script pushScript(List<JsonNode> alerts, List<JsonNode> syncAlerts, boolean closeAfterPush) {
    return (idx, s) -> {
      s.handshake(true);
      for (int i = 0; i < alerts.size(); i++) {
        s.pushAlert(alerts.get(i), "push-" + idx + "-" + i);
      }
      if (closeAfterPush) { s.rstClose(); return; }
      s.recordLoop(syncAlerts, false);
    };
  }
}
