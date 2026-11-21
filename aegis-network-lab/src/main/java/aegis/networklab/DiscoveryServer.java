package aegis.networklab;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP discovery responder (Bài 7): listens on {@link LabDefaults#DISCOVERY_PORT} (configurable),
 * answers DISCOVER_EBS {nonce,version} with {serverHost,tlsPort,nonce,serverId}, echoing the
 * request nonce verbatim. Malformed datagrams are dropped, never answered. The reply itself is
 * advisory — trust is established later over TLS by the client.
 */
public final class DiscoveryServer implements AutoCloseable {

  public record Config(int port, String serverHost, int tlsPort, String serverId) {
    public Config {
      if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
      if (serverHost == null || serverHost.isBlank()) throw new IllegalArgumentException("blank serverHost");
      if (tlsPort < 1 || tlsPort > 65535) throw new IllegalArgumentException("tlsPort out of range");
      if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("blank serverId");
    }
  }

  private final Config cfg;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final DatagramSocket socket;
  private final Thread worker;
  private volatile boolean running = true;
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong replies = new AtomicLong();
  private final AtomicLong invalidDropped = new AtomicLong();

  public DiscoveryServer(Config cfg) throws SocketException {
    this.cfg = cfg;
    this.socket = cfg.port() == 0 ? new DatagramSocket(null) : new DatagramSocket(cfg.port());
    if (cfg.port() == 0) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(LabDefaults.SERVER_HOST, 0));
    }
    this.worker = new Thread(this::loop, "lab-discovery-" + socket.getLocalPort());
    this.worker.setDaemon(true);
  }

  public void start() { worker.start(); }

  public int port() { return socket.getLocalPort(); }
  public long requestsReceived() { return requests.get(); }
  public long repliesSent() { return replies.get(); }
  public long invalidDropped() { return invalidDropped.get(); }

  private void loop() {
    byte[] buf = new byte[LabDatagramCodec.MAX_DATAGRAM_BYTES];
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    while (running) {
      try {
        packet.setLength(buf.length);
        socket.receive(packet);
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        requests.incrementAndGet();
        DiscoverRequest req;
        try {
          req = codec.decodeRequest(data);
        } catch (LabCodecException ex) {
          invalidDropped.incrementAndGet();
          continue;
        }
        byte[] reply = codec.encode(new DiscoverResponse(
            cfg.serverHost(), cfg.tlsPort(), req.nonce(), cfg.serverId()));
        socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
        replies.incrementAndGet();
      } catch (LabCodecException ex) {
        invalidDropped.incrementAndGet(); // cannot even encode a reply for a weird request
      } catch (IOException ex) {
        if (running) {
          // transient receive/send error; keep serving
          try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
      }
    }
  }

  @Override
  public void close() {
    running = false;
    socket.close(); // unblocks receive()
    try { worker.join(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
  }
}
