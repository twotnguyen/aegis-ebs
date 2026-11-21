package aegis.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Plain-WebSocket status feed endpoint (ws://localhost:9444, status/1
 * contract). Hand-rolled RFC6455 handshake — no external deps. Every path is
 * accepted (the dashboard connects to "/"); "resync" text frames trigger a
 * fresh STATUS_SNAPSHOT.
 *
 * <p><b>Trust model (S-05):</b> this is a trusted-LAN read plane for the
 * dashboard — it carries building status only (no credentials, no command
 * capability) and is documented as such in docs/SECURITY.md. Role/scope-gated
 * reads go through TLS STATUS_SUBSCRIBE instead. To bound resource use the
 * feed caps concurrent subscribers ({@value #MAX_SUBSCRIBERS}); excess
 * connections are rejected with HTTP 503 before the upgrade.
 */
public final class StatusFeedServer {
  private static final Logger LOG = LoggerFactory.getLogger(StatusFeedServer.class);
  private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  /** Subscriber cap (S-05): bounds memory/threads of the unauthenticated read plane. */
  static final int MAX_SUBSCRIBERS = 64;

  private final int port;
  private final StatusFeedService feed;
  private final Executor executor;
  private volatile ServerSocket serverSocket;
  private volatile boolean running = false;

  public StatusFeedServer(int port, StatusFeedService feed, Executor executor) {
    this.port = port;
    this.feed = feed;
    this.executor = executor;
  }

  public void start() throws IOException {
    serverSocket = new ServerSocket(port);
    running = true;
    executor.execute(this::acceptLoop);
    LOG.info("status feed listening on ws://localhost:{}/ (status/1)", serverSocket.getLocalPort());
  }

  public int port() {
    ServerSocket s = serverSocket;
    return s == null ? port : s.getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        executor.execute(() -> handle(socket));
      } catch (IOException e) {
        if (running) LOG.warn("status accept failed: {}", e.toString());
        return;
      }
    }
  }

  private void handle(Socket socket) {
    WsConnection conn = null;
    try {
      socket.setTcpNoDelay(true);
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String requestLine = reader.readLine();
      if (requestLine == null) { socket.close(); return; }
      Map<String, String> headers = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
      }
      String key = headers.get("sec-websocket-key");
      String upgrade = headers.getOrDefault("upgrade", "");
      if (key == null || !upgrade.equalsIgnoreCase("websocket")) {
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
        socket.close();
        return;
      }
      if (feed.subscriberCount() >= MAX_SUBSCRIBERS) {
        // S-05: bound the unauthenticated read plane; reject before the upgrade.
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
        socket.close();
        return;
      }
      String accept = Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8)));
      OutputStream out = socket.getOutputStream();
      String response = "HTTP/1.1 101 Switching Protocols\r\n"
          + "Upgrade: websocket\r\n"
          + "Connection: Upgrade\r\n"
          + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
      out.write(response.getBytes(StandardCharsets.UTF_8));
      out.flush();

      conn = new WsConnection(socket);
      feed.subscribe(conn); // sends STATUS_SNAPSHOT immediately
      final WsConnection c = conn;
      conn.readLoop(text -> {
        if (text.trim().equalsIgnoreCase("resync")) {
          c.sendText(Json.write(feed.snapshot()));
        }
      });
    } catch (Exception e) {
      LOG.debug("status connection ended: {}", e.toString());
    } finally {
      if (conn != null) {
        feed.unsubscribe(conn);
        conn.close();
      } else {
        try { socket.close(); } catch (IOException ignored) { }
      }
    }
  }

  public void stop() {
    running = false;
    feed.closeAll();
    try {
      ServerSocket s = serverSocket;
      if (s != null) s.close();
    } catch (IOException ignored) {
      // shutting down
    }
  }
}
