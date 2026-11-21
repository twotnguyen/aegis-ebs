package aegis.networklab;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unicast fallback publisher: one UDP datagram PER registered subscriber (N copies on the source
 * link — the cost multicast avoids). Used when multicast is unavailable, e.g. macOS loopback.
 */
public final class UnicastHintPublisher implements HintPublisher {

  private final DatagramSocket socket;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final List<InetSocketAddress> targets = new CopyOnWriteArrayList<>();
  private final AtomicLong packets = new AtomicLong();
  private final AtomicLong bytes = new AtomicLong();

  public UnicastHintPublisher() throws IOException {
    this.socket = new DatagramSocket(null);
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(0));
  }

  public void addTarget(InetSocketAddress target) { targets.add(target); }
  public int targetCount() { return targets.size(); }

  @Override
  public void publish(AlertHint hint) throws IOException, LabCodecException {
    byte[] data = codec.encode(hint);
    for (InetSocketAddress t : targets) {
      socket.send(new DatagramPacket(data, data.length, t));
      packets.incrementAndGet();
      bytes.addAndGet(data.length);
    }
  }

  @Override public long packetsSent() { return packets.get(); }
  @Override public long bytesSent() { return bytes.get(); }
  @Override public void close() { socket.close(); }
}
