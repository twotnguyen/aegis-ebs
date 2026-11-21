package aegis.networklab;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * Multicast settings (Bài 8): group, port, TTL (max routers a datagram may cross, Bài 7.1.1)
 * and outgoing interface. {@code interfaceName == null} → JVM default route interface.
 * On macOS loopback (lo0) multicast sends succeed but are never delivered — use
 * {@link MulticastSupport#probe} and the unicast fallback there.
 */
public record MulticastConfig(String group, int port, int ttl, String interfaceName, boolean loop) {

  public MulticastConfig {
    if (group == null) throw new IllegalArgumentException("null group");
    InetAddress g;
    try {
      g = InetAddress.getByName(group);
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("cannot resolve group: " + group, ex);
    }
    if (!g.isMulticastAddress())
      throw new IllegalArgumentException("not a multicast (class D) address: " + group);
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    if (ttl < 0 || ttl > 255) throw new IllegalArgumentException("ttl out of range 0..255: " + ttl);
  }

  public static MulticastConfig defaults() {
    return new MulticastConfig(LabDefaults.MULTICAST_GROUP, LabDefaults.MULTICAST_PORT,
        LabDefaults.MULTICAST_TTL, null, true);
  }

  public MulticastConfig withPort(int p) { return new MulticastConfig(group, p, ttl, interfaceName, loop); }
  public MulticastConfig withTtl(int t) { return new MulticastConfig(group, port, t, interfaceName, loop); }
  public MulticastConfig withInterface(String ni) { return new MulticastConfig(group, port, ttl, ni, loop); }

  /** Resolve the configured interface; null = default. Throws if a named interface is absent. */
  public NetworkInterface resolveInterface() throws SocketException {
    if (interfaceName == null) return null;
    NetworkInterface ni = NetworkInterface.getByName(interfaceName);
    if (ni == null) throw new SocketException("no such network interface: " + interfaceName);
    return ni;
  }

  public InetAddress groupAddress() throws UnknownHostException {
    return InetAddress.getByName(group);
  }
}
