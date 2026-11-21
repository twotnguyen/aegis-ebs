package aegis.networklab;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/** Unicast fallback subscriber: plain DatagramSocket on an ephemeral loopback port. */
public final class UnicastHintSubscriber implements HintSubscriber {

  private final DatagramSocket socket;
  private final HintListener listener;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final Thread worker;
  private volatile boolean running = true;
  private final AtomicLong hints = new AtomicLong();
  private final AtomicLong bytes = new AtomicLong();
  private final AtomicLong invalid = new AtomicLong();

  public UnicastHintSubscriber(HintListener listener) throws IOException {
    this.listener = listener;
    this.socket = new DatagramSocket(null);
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress("127.0.0.1", 0));
    this.worker = new Thread(this::loop, "lab-unicast-sub-" + socket.getLocalPort());
    this.worker.setDaemon(true);
    this.worker.start();
  }

  private void loop() {
    byte[] buf = new byte[LabDatagramCodec.MAX_DATAGRAM_BYTES];
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    while (running) {
      try {
        packet.setLength(buf.length);
        socket.receive(packet);
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        AlertHint hint;
        try {
          hint = codec.decodeHint(data);
        } catch (LabCodecException ex) {
          invalid.incrementAndGet();
          continue;
        }
        hints.incrementAndGet();
        bytes.addAndGet(data.length);
        try {
          listener.onHint(hint);
        } catch (RuntimeException ex) {
          // keep receiving
        }
      } catch (IOException ex) {
        if (!running) return;
      }
    }
  }

  @Override public long hintsReceived() { return hints.get(); }
  @Override public long bytesReceived() { return bytes.get(); }
  @Override public long invalidDropped() { return invalid.get(); }

  @Override
  public InetSocketAddress unicastEndpoint() {
    return new InetSocketAddress("127.0.0.1", socket.getLocalPort());
  }

  @Override
  public void close() {
    running = false;
    socket.close();
    try { worker.join(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
  }
}
