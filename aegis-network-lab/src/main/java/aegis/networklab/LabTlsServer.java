package aegis.networklab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.FrameException;
import aegis.protocol.MessageType;
import aegis.protocol.Protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal lab TLS endpoint speaking EBSP/2.0 (reuses aegis-protocol FrameCodecImpl + Envelope):
 * HELLO → SERVER_HELLO {serverId, nonce echo, version}; SYNC_REQUEST {alertId} →
 * SYNC_RESULT {found, alert{...}} from the authoritative {@link AlertDirectory}.
 * This is the trusted channel the discovery client verifies against and the hint subscriber
 * fetches from. The real aegis-server can replace it without changing the wire contract.
 */
public final class LabTlsServer implements AutoCloseable {

  private final SSLContext sslContext;
  private final String serverId;
  private final AlertDirectory directory;
  private final ObjectMapper mapper = new ObjectMapper();
  private final FrameCodec codec = new FrameCodecImpl(mapper);
  private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
  private SSLServerSocket serverSocket;
  private Thread acceptor;
  private volatile boolean running;

  public LabTlsServer(SSLContext sslContext, String serverId, AlertDirectory directory) {
    this.sslContext = sslContext;
    this.serverId = serverId;
    this.directory = directory;
  }

  /** Bind and start; port 0 → ephemeral. Returns the actual port. */
  public int start(int port) throws IOException {
    serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(port);
    running = true;
    acceptor = new Thread(this::acceptLoop, "lab-tls-" + serverSocket.getLocalPort());
    acceptor.setDaemon(true);
    acceptor.start();
    return serverSocket.getLocalPort();
  }

  public int port() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

  private void acceptLoop() {
    while (running) {
      try {
        SSLSocket s = (SSLSocket) serverSocket.accept();
        connections.add(s);
        Thread t = new Thread(() -> serve(s), "lab-tls-conn-" + s.getPort());
        t.setDaemon(true);
        t.start();
      } catch (IOException ex) {
        if (!running) return;
      }
    }
  }

  private void serve(SSLSocket s) {
    try (s) {
      // Lab endpoint: subscribers keep idle TLS connections between hints — no idle timeout.
      // (Production EBSP uses HEARTBEAT/HEARTBEAT_ACK every 10s instead of relying on this.)
      s.setSoTimeout(0);
      Envelope hello = codec.readFrame(s.getInputStream());
      if (!MessageType.HELLO.name().equals(hello.type())) {
        codec.writeFrame(s.getOutputStream(), error(hello, "EXPECTED_HELLO"));
        return;
      }
      JsonNode nonceNode = hello.payload().get("nonce");
      if (nonceNode == null || !nonceNode.isTextual() || nonceNode.asText().isBlank()) {
        codec.writeFrame(s.getOutputStream(), error(hello, "HELLO_MISSING_NONCE"));
        return;
      }
      ObjectNode helloPayload = mapper.createObjectNode();
      helloPayload.put("serverId", serverId);
      helloPayload.put("nonce", nonceNode.asText()); // echo binds TLS session to discovery exchange
      helloPayload.put("version", Protocol.VERSION);
      codec.writeFrame(s.getOutputStream(),
          envelope(MessageType.SERVER_HELLO, helloPayload, hello.messageId()));
      while (running) {
        Envelope req;
        try {
          req = codec.readFrame(s.getInputStream());
        } catch (EOFException eof) {
          return; // client closed
        }
        if (MessageType.SYNC_REQUEST.name().equals(req.type())) {
          JsonNode idNode = req.payload().get("alertId");
          String alertId = idNode != null && idNode.isTextual() ? idNode.asText() : null;
          ObjectNode payload = mapper.createObjectNode();
          payload.put("alertId", alertId);
          var found = alertId == null ? java.util.Optional.<AlertContent>empty() : directory.find(alertId);
          payload.put("found", found.isPresent());
          found.ifPresent(c -> payload.set("alert", alertNode(c)));
          codec.writeFrame(s.getOutputStream(), envelope(MessageType.SYNC_RESULT, payload, req.messageId()));
        } else {
          codec.writeFrame(s.getOutputStream(), error(req, "UNSUPPORTED_TYPE"));
        }
      }
    } catch (IOException | FrameException ex) {
      // connection ended or malformed frame — drop it
    } finally {
      connections.remove(s);
    }
  }

  private ObjectNode alertNode(AlertContent c) {
    ObjectNode n = mapper.createObjectNode();
    n.put("alertId", c.alertId());
    n.put("revision", c.revision());
    n.put("title", c.title());
    n.put("body", c.body());
    n.put("priority", c.priority());
    n.put("issuedAt", c.issuedAt());
    return n;
  }

  private Envelope error(Envelope cause, String code) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("code", code);
    payload.put("detail", "lab endpoint rejected the request");
    return envelope(MessageType.ERROR, payload, cause == null ? null : cause.messageId());
  }

  private Envelope envelope(MessageType type, JsonNode payload, String correlationId) {
    return new Envelope(Protocol.VERSION, type.name(), UUID.randomUUID().toString(),
        correlationId, Instant.now().toString(), payload);
  }

  @Override
  public void close() {
    running = false;
    try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) { }
    for (Socket s : connections) {
      try { s.close(); } catch (IOException ignore) { }
    }
    if (acceptor != null) {
      try { acceptor.join(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
  }
}
