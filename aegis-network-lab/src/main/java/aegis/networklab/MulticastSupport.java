package aegis.networklab;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;

/**
 * Real send/receive probe: joins the group on an EPHEMERAL port (so lab subscribers on the
 * configured port are not disturbed), sends a LAB_PROBE datagram to itself and waits.
 * This catches the macOS-lo0 case where join+send succeed but nothing is ever delivered
 * (no multicast route on loopback) — a plain "can I join?" check would wrongly report success.
 */
public final class MulticastSupport {

  public record ProbeResult(boolean available, String detail, String interfaceUsed) {}

  private static final byte[] PROBE =
      "{\"type\":\"LAB_PROBE\"}".getBytes(StandardCharsets.UTF_8);
  private static final int PROBE_TIMEOUT_MS = 800;

  private MulticastSupport() {}

  public static ProbeResult probe(MulticastConfig cfg) {
    String ifLabel = cfg.interfaceName() == null ? "default" : cfg.interfaceName();
    try {
      InetAddress group = cfg.groupAddress();
      NetworkInterface ni = cfg.resolveInterface();
      try (MulticastSocket s = new MulticastSocket(null)) {
        s.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        s.bind(new InetSocketAddress(0));
        int port = s.getLocalPort();
        s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, cfg.loop());
        s.setOption(StandardSocketOptions.IP_MULTICAST_TTL, cfg.ttl());
        if (ni != null) s.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
        InetSocketAddress mcast = new InetSocketAddress(group, port);
        s.joinGroup(mcast, ni);
        try {
          s.setSoTimeout(PROBE_TIMEOUT_MS);
          // UDP is unreliable (Bài 7.1.3): one lost probe ≠ dead multicast. Try 3 times.
          for (int attempt = 1; attempt <= 3; attempt++) {
            s.send(new DatagramPacket(PROBE, PROBE.length, group, port));
            DatagramPacket p = new DatagramPacket(new byte[256], 256);
            try {
              s.receive(p);
            } catch (SocketTimeoutException te) {
              continue;
            }
            String got = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            if (!new String(PROBE, StandardCharsets.UTF_8).equals(got))
              return new ProbeResult(false, "probe received unexpected payload: " + got, ifLabel);
            String used = ni != null ? ni.getName()
                : (s.getOption(StandardSocketOptions.IP_MULTICAST_IF) != null
                    ? s.getOption(StandardSocketOptions.IP_MULTICAST_IF).getName() : "default");
            return new ProbeResult(true,
                "multicast loopback verified on " + group.getHostAddress() + ":" + port
                    + " (ttl=" + cfg.ttl() + ", attempt " + attempt + ")", used);
          }
          return new ProbeResult(false,
              "3 probes sent, no loopback delivery within " + (3 * PROBE_TIMEOUT_MS) + "ms on " + ifLabel
                  + " (typical macOS lo0: no multicast route) — use unicast fallback", ifLabel);
        } finally {
          try { s.leaveGroup(mcast, ni); } catch (Exception ignore) { /* closing anyway */ }
        }
      }
    } catch (Exception ex) {
      return new ProbeResult(false, "multicast unavailable on " + ifLabel + ": " + ex, ifLabel);
    }
  }
}
