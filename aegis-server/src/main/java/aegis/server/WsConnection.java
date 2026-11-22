package aegis.server;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Minimal RFC6455 WebSocket connection (server side, text frames only).
 * Server-&gt;client frames are unmasked; client frames MUST be masked and are
 * unmasked here. Handles ping/pong + close handshake; oversized frames are
 * rejected before allocation.
 *
 * <p>H4 (backend-depth-audit): outbound text frames go through a bounded queue
 * drained by a dedicated virtual writer thread, so {@link #sendText} never blocks
 * the caller. The status-feed broadcast runs on a shared timer pool; a slow or
 * unauthenticated subscriber that stops draining its socket would otherwise block
 * {@code out.write} and stall the whole feed (and pin a timer thread). With the
 * queue, a subscriber that falls behind (queue full) is simply closed and
 * unsubscribed — one slow peer can never head-of-line-block the others.
 */
public final class WsConnection {
  private static final int MAX_FRAME = 1 << 20; // 1 MiB guard
  private static final int OUT_QUEUE_CAP = 256; // bounded outbound backlog per subscriber

  private final Socket socket;
  private final DataInputStream in;
  private final OutputStream out;
  private final Object writeLock = new Object();
  private final LinkedBlockingQueue<String> outQueue = new LinkedBlockingQueue<>(OUT_QUEUE_CAP);
  private final Thread writer;
  private volatile boolean closed = false;

  public WsConnection(Socket socket) throws IOException {
    this.socket = socket;
    this.in = new DataInputStream(socket.getInputStream());
    this.out = socket.getOutputStream();
    this.writer = Thread.ofVirtual().name("ws-writer").start(this::writeLoop);
  }

  /**
   * Enqueues one text frame for the async writer; non-blocking. Returns false when
   * the peer is gone or the subscriber has fallen behind (queue full) — the caller
   * unsubscribes/closes it. Never blocks the broadcast/timer thread on a slow peer.
   */
  public boolean sendText(String text) {
    if (closed) return false;
    if (!outQueue.offer(text)) {
      // Slow subscriber: backlog full. Drop it instead of blocking the feed (H4).
      close();
      return false;
    }
    return true;
  }

  /** Drains the outbound queue on a dedicated thread; blocking writes live here only. */
  private void writeLoop() {
    try {
      while (!closed) {
        String text = outQueue.take();
        if (!writeFrame(text)) return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // close() interrupted us
    }
  }

  /** Writes one unmasked text frame (blocking); false when the peer is gone. */
  private boolean writeFrame(String text) {
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
    writer.interrupt(); // wake the writer if parked on outQueue.take()
    try {
      socket.close(); // also unblocks a writer parked on out.write
    } catch (IOException ignored) {
      // already broken
    }
  }

  public boolean isClosed() { return closed; }
}
