package aegis.networklab;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.StandardSocketOptions;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real multicast publisher (Bài 8.3.1): MulticastSocket → joinGroup → TTL/interface options →
 * send ALERT_HINT datagrams to group:port → leaveGroup on close. ONE datagram per hint regardless
 * of subscriber count — replication happens in the network (Hình 8.1), not at the source.
 */
public final class MulticastHintPublisher implements HintPublisher {

  private final MulticastConfig cfg;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final MulticastSocket socket;
  private final InetAddress group;
  private final NetworkInterface ni;
  private final InetSocketAddress target;
  private final AtomicLong packets = new AtomicLong();
  private final AtomicLong bytes = new AtomicLong();

  public MulticastHintPublisher(MulticastConfig cfg) throws IOException {
    this.cfg = cfg;
    this.group = cfg.groupAddress();
    this.ni = cfg.resolveInterface();
    this.target = new InetSocketAddress(group, cfg.port());
    this.socket = new MulticastSocket(null);
    boolean ok = false;
    try {
      socket.setOption(StandardSocketOptions.SO_REUSEADDR, true);
      socket.bind(new InetSocketAddress(0));
      socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, cfg.loop());
      socket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, cfg.ttl());
      if (ni != null) socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
      socket.joinGroup(target, ni);
      ok = true;
    } finally {
      if (!ok) socket.close();
    }
  }

  public int ttl() throws IOException { return socket.getOption(StandardSocketOptions.IP_MULTICAST_TTL); }

  @Override
  public void publish(AlertHint hint) throws IOException, LabCodecException {
    byte[] data = codec.encode(hint);
    socket.send(new DatagramPacket(data, data.length, group, cfg.port()));
    packets.incrementAndGet();
    bytes.addAndGet(data.length);
  }

  @Override public long packetsSent() { return packets.get(); }
  @Override public long bytesSent() { return bytes.get(); }

  @Override
  public void close() {
    try { socket.leaveGroup(target, ni); } catch (Exception ignore) { }
    socket.close();
  }
}
