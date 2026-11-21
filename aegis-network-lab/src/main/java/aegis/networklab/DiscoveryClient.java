package aegis.networklab;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Discovery client. TWO-STAGE trust model (docs/PROTOCOL.md line 11):
 * <ol>
 *   <li>{@link #discoverUntrusted()} — UDP exchange; the nonce echo proves the reply belongs to
 *       this exchange (blocks blind replay/spoof of a <em>different</em> exchange), but UDP is
 *       unauthenticated: the result is only a pointer, never trusted content.</li>
 *   <li>{@link #discoverTrusted(SSLContext)} — additionally opens TLS to the advertised endpoint
 *       against a PINNED trust store, requires the certificate CN to equal the advertised
 *       serverId, and performs an EBSP HELLO/SERVER_HELLO over TLS carrying the SAME nonce
 *       (binds the UDP discovery to this authenticated session). Only then is a
 *       {@link TrustedServer} returned. Any failure → {@link DiscoveryRejectedException}.</li>
 * </ol>
 */
public final class DiscoveryClient {

  public record Config(String host, int port, int timeoutMillis, String expectedServerId) {
    public Config {
      if (host == null || host.isBlank()) throw new IllegalArgumentException("blank host");
      if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
      if (timeoutMillis < 1) throw new IllegalArgumentException("timeout must be >= 1ms");
    }
    public static Config defaults() {
      return new Config(LabDefaults.SERVER_HOST, LabDefaults.DISCOVERY_PORT, 2000, null);
    }
  }

  /** Stage-1 result: UNTRUSTED pointer from UDP. Do not fetch/display based on this alone. */
  public record UntrustedEndpoint(String serverHost, int tlsPort, String nonce, String serverId) {}

  /** Stage-2 result: TLS-authenticated endpoint (pinned cert, CN==serverId, nonce echoed). */
  public record TrustedServer(String serverHost, int tlsPort, String serverId, String nonce) {}

  private final Config cfg;
  private final LabDatagramCodec codec = new LabDatagramCodec();
  private final SecureRandom random = new SecureRandom();

  public DiscoveryClient(Config cfg) { this.cfg = cfg; }

  public String newNonce() {
    byte[] b = new byte[16];
    random.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  public UntrustedEndpoint discoverUntrusted() throws IOException, DiscoveryRejectedException {
    return discoverUntrusted(newNonce());
  }

  /** Nonce-injecting variant (tests/replay demos). */
  public UntrustedEndpoint discoverUntrusted(String nonce) throws IOException, DiscoveryRejectedException {
    byte[] req;
    try {
      req = codec.encode(new DiscoverRequest(nonce, aegis.protocol.Protocol.VERSION));
    } catch (LabCodecException ex) {
      throw new DiscoveryRejectedException("cannot build DISCOVER_EBS", ex);
    }
    try (DatagramSocket socket = new DatagramSocket()) {
      InetAddress addr = InetAddress.getByName(cfg.host());
      socket.send(new DatagramPacket(req, req.length, addr, cfg.port()));
      long deadline = System.nanoTime() + cfg.timeoutMillis() * 1_000_000L;
      byte[] buf = new byte[LabDatagramCodec.MAX_DATAGRAM_BYTES];
      while (true) {
        long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainMs <= 0)
          throw new DiscoveryRejectedException("no DISCOVER_EBS reply within " + cfg.timeoutMillis() + "ms");
        socket.setSoTimeout((int) Math.max(1, remainMs));
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
          socket.receive(packet);
        } catch (SocketTimeoutException ex) {
          throw new DiscoveryRejectedException("no DISCOVER_EBS reply within " + cfg.timeoutMillis() + "ms", ex);
        }
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        DiscoverResponse resp;
        try {
          resp = codec.decodeResponse(data);
        } catch (LabCodecException ex) {
          continue; // ignore garbage / unrelated datagrams until deadline
        }
        if (!nonce.equals(resp.nonce()))
          throw new DiscoveryRejectedException("nonce echo mismatch: sent " + nonce + ", got " + resp.nonce()
              + " — possible spoof/replay, reply rejected");
        if (cfg.expectedServerId() != null && !cfg.expectedServerId().equals(resp.serverId()))
          throw new DiscoveryRejectedException("serverId " + resp.serverId()
              + " != expected " + cfg.expectedServerId());
        return new UntrustedEndpoint(resp.serverHost(), resp.tlsPort(), resp.nonce(), resp.serverId());
      }
    }
  }

  /**
   * Full trust gate: UDP discovery + pinned-TLS handshake + CN==serverId + HELLO nonce echo.
   * The returned endpoint is the ONLY sanctioned basis for fetching alert content.
   */
  public TrustedServer discoverTrusted(SSLContext pinnedTls) throws IOException, DiscoveryRejectedException {
    UntrustedEndpoint u = discoverUntrusted();
    try (LabTlsClient client = LabTlsClient.connectVerified(
        u.serverHost(), u.tlsPort(), pinnedTls, u.serverId(), u.nonce())) {
      return new TrustedServer(u.serverHost(), u.tlsPort(), client.serverId(), u.nonce());
    } catch (LabCodecException | aegis.protocol.FrameException ex) {
      throw new DiscoveryRejectedException("TLS verification handshake failed: " + ex.getMessage(), ex);
    } catch (IOException ex) {
      throw new DiscoveryRejectedException("cannot establish pinned TLS to "
          + u.serverHost() + ":" + u.tlsPort() + ": " + ex.getMessage(), ex);
    }
  }
}
