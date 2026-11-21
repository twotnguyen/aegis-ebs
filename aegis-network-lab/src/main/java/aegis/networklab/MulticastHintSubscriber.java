package aegis.networklab;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real multicast subscriber (Bài 8.3.2): bind port → joinGroup(group, interface) → receive loop
 * → codec validation → listener. SO_REUSEADDR lets several subscribers share the group port on
 * BSD/macOS. Invalid datagrams are dropped and counted, never passed to the listener.
 */
public final class MulticastHintSubscriber implements HintSubscriber {

  private final MulticastSocket socket;
  private final InetSocketAddress group;
  private final NetworkInterface ni;
  private final HintListener listener;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final Thread worker;
  private volatile boolean running = true;
  private final AtomicLong hints = new AtomicLong();
  private final AtomicLong bytes = new AtomicLong();
  private final AtomicLong invalid = new AtomicLong();

  public MulticastHintSubscriber(MulticastConfig cfg, HintListener listener) throws IOException {
    this.listener = listener;
    this.ni = cfg.resolveInterface();
    this.group = new InetSocketAddress(cfg.groupAddress(), cfg.port());
    this.socket = new MulticastSocket(null);
    boolean ok = false;
    try {
      socket.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      socket.bind(new InetSocketAddress(cfg.port()));
      socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, cfg.loop());
      if (ni != null) socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
      socket.joinGroup(group, ni);
      ok = true;
    } finally {
      if (!ok) socket.close();
    }
    this.worker = new Thread(this::loop, "lab-mcast-sub-" + cfg.port());
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
          // listener bugs must not kill the receive loop
        }
      } catch (IOException ex) {
        if (!running) return;
      }
    }
  }

  @Override public long hintsReceived() { return hints.get(); }
  @Override public long bytesReceived() { return bytes.get(); }
  @Override public long invalidDropped() { return invalid.get(); }
  @Override public InetSocketAddress unicastEndpoint() { return null; }

  @Override
  public void close() {
    running = false;
    try { socket.leaveGroup(group, ni); } catch (Exception ignore) { }
    socket.close();
    try { worker.join(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
  }
}
