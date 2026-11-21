package aegis.server;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RFC6455 WebSocket connection (server side, text frames only).
 * Server-&gt;client frames are unmasked; client frames MUST be masked and are
 * unmasked here. Handles ping/pong + close handshake; oversized frames are
 * rejected before allocation.
 */
public final class WsConnection {
  private static final int MAX_FRAME = 1 << 20; // 1 MiB guard

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final Object writeLock = new Object();
  private volatile boolean closed = false;

  public WsConnection(Socket socket) throws IOException {
    this.socket = socket;
    this.in = new DataInputStream(socket.getInputStream());
    this.out = socket.getOutputStream();
  }

  /** Writes one unmasked text frame; false when the peer is gone. */
  public boolean sendText(String text) {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    try {
      synchronized (writeLock) {
        if (closed) return false;
        out.write(0x81); // FIN + text opcode
        if (payload.length < 126) {
          out.write(payload.length);
        } else if (payload.length < 65536) {
          out.write(126);
          out.write((payload.length >>> 8) & 0xFF);
          out.write(payload.length & 0xFF);
        } else {
          out.write(127);
          for (int i = 7; i >= 0; i--) out.write((int) ((long) payload.length >>> (8 * i)) & 0xFF);
        }
        out.write(payload);
        out.flush();
      }
      return true;
    } catch (IOException e) {
      close();
      return false;
    }
  }

  private void sendPong(byte[] payload) {
    try {
      synchronized (writeLock) {
        if (closed) return;
        out.write(0x8A); // FIN + pong
        out.write(payload.length); // ping payloads are <= 125 per RFC
        out.write(payload);
        out.flush();
      }
    } catch (IOException e) {
      close();
    }
  }

  private void sendClose() {
    try {
      synchronized (writeLock) {
        if (closed) return;
        out.write(0x88);
        out.write(0);
        out.flush();
      }
    } catch (IOException ignored) {
      // peer already gone
    }
  }

  /**
   * Blocking read loop. Returns when the peer closes or the link breaks.
   * Text frames from the client are ignored except "resync" (contract: the
   * server snapshots again after a resync request).
   */
  public void readLoop(java.util.function.Consumer<String> onText) {
    try {
      while (!closed) {
        int b0 = in.read();
        if (b0 < 0) return;
        int b1 = in.read();
        if (b1 < 0) return;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
          len = in.readUnsignedShort();
        } else if (len == 127) {
          len = in.readLong();
        }
        if (len < 0 || len > MAX_FRAME) {
          close();
          return;
        }
        byte[] mask = new byte[4];
        if (masked) in.readFully(mask);
        byte[] payload = new byte[(int) len];
        in.readFully(payload);
        if (masked) {
          for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        switch (opcode) {
          case 0x8 -> { sendClose(); return; }        // close
          case 0x9 -> sendPong(payload);              // ping
          case 0xA -> { /* pong: ignore */ }
          case 0x1 -> {                               // text
            String text = new String(payload, StandardCharsets.UTF_8);
            if (onText != null) onText.accept(text);
          }
          default -> { /* binary/continuation unsupported: ignore */ }
        }
      }
    } catch (IOException e) {
      // peer vanished; fall through to close
    } finally {
      close();
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    try {
      socket.close();
    } catch (IOException ignored) {
      // already broken
    }
  }

  public boolean isClosed() { return closed; }
}
