package aegis.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Minimal RFC6455 WebSocket client for the status/1 feed tests: performs the
 * HTTP upgrade handshake (verifying Sec-WebSocket-Accept), then demuxes text
 * frames into per-type queues.
 */
final class TestWsClient implements AutoCloseable {

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final Map<String, BlockingQueue<JsonNode>> queues = new ConcurrentHashMap<>();
  private volatile boolean closed = false;
  private final Thread reader;

  TestWsClient(int port) throws Exception {
    socket = new Socket("localhost", port);
    socket.setTcpNoDelay(true);
    in = new DataInputStream(socket.getInputStream());
    out = socket.getOutputStream();
    String key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    String request = "GET / HTTP/1.1\r\n"
        + "Host: localhost:" + port + "\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + "Sec-WebSocket-Key: " + key + "\r\n"
        + "Sec-WebSocket-Version: 13\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.UTF_8));
    out.flush();
    // read the 101 response head
    StringBuilder head = new StringBuilder();
    int b;
    while ((b = in.read()) >= 0) {
      head.append((char) b);
      if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
    }
    String response = head.toString();
    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101, got: " + response);
    String expectedAccept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
    assertTrue(response.contains("Sec-WebSocket-Accept: " + expectedAccept),
        "bad accept key in: " + response);
    reader = new Thread(this::readLoop, "test-ws-reader");
    reader.setDaemon(true);
    reader.start();
  }

  private void readLoop() {
    try {
      while (!closed) {
        int b0 = in.read();
        if (b0 < 0) break;
        int b1 = in.read();
        if (b1 < 0) break;
        int opcode = b0 & 0x0F;
        long len = b1 & 0x7F;
        if (len == 126) len = in.readUnsignedShort();
        else if (len == 127) len = in.readLong();
        byte[] payload = new byte[(int) len];
        in.readFully(payload);
        if (opcode == 0x8) break;             // close
        if (opcode == 0x9) {                  // ping -> pong (masked, as a client)
          synchronized (this) {
            out.write(0x8A);
            out.write(0x80 | payload.length);
            byte[] mask = {1, 2, 3, 4};
            out.write(mask);
            for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
            out.flush();
          }
          continue;
        }
        if (opcode == 0x1) {
          JsonNode node = Json.MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
          String type = node.path("type").asText("UNKNOWN");
          queues.computeIfAbsent(type, k -> new LinkedBlockingQueue<>()).offer(node);
        }
      }
    } catch (IOException e) {
      // server closed
    } finally {
      closed = true;
    }
  }

  JsonNode await(String type, long timeoutMs) {
    try {
      long end = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < end) {
        JsonNode node = queues.computeIfAbsent(type, k -> new LinkedBlockingQueue<>())
            .poll(50, TimeUnit.MILLISECONDS);
        if (node != null) return node;
        if (closed) fail("ws closed while awaiting " + type);
      }
      fail("timed out awaiting ws " + type);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  void sendText(String text) throws IOException {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    byte[] mask = {7, 7, 7, 7};
    synchronized (this) {
      out.write(0x81);
      out.write(0x80 | payload.length); // assume < 126 for test commands
      out.write(mask);
      for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
      out.flush();
    }
  }

  boolean isClosed() { return closed || socket.isClosed(); }

  @Override
  public void close() {
    closed = true;
    try {
      socket.close();
    } catch (IOException ignored) {
      // already gone
    }
    reader.interrupt();
  }
}
