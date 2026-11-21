package aegis.networklab;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unicast fallback: (a) direct unicast pub/sub always works on loopback;
 * (b) forced macOS-lo0 multicast config → probe detects "send ok, no delivery" →
 * FallbackHintBus automatically switches to unicast and hints still reach every subscriber,
 * who fetch verified content over TLS.
 */
class UnicastFallbackTest {

  @TempDir
  Path certDir;

  @Test
  void directUnicastPubSubDeliversHints() throws Exception {
    List<AlertHint> a = new CopyOnWriteArrayList<>();
    List<AlertHint> b = new CopyOnWriteArrayList<>();
    try (UnicastHintPublisher pub = new UnicastHintPublisher();
         UnicastHintSubscriber sa = new UnicastHintSubscriber(a::add);
         UnicastHintSubscriber sb = new UnicastHintSubscriber(b::add)) {
      pub.addTarget(sa.unicastEndpoint());
      pub.addTarget(sb.unicastEndpoint());
      pub.publish(new AlertHint("alert-x", 1, "s"));
      pub.publish(new AlertHint("alert-x", 2, "s"));
      MulticastLoopbackTest.await(() -> a.size() == 2 && b.size() == 2, 5000);
      assertEquals(2, a.size());
      assertEquals(2, b.size());
      assertEquals(2, a.get(1).revision());
      assertEquals(4, pub.packetsSent(), "unicast = one copy per subscriber per hint");
      assertEquals(0, sa.invalidDropped());
    }
  }

  @Test
  void macOsLoopbackForcesFallbackAndStillDeliversVerifiedContent() throws Exception {
    NetworkInterface lo0 = NetworkInterface.getByName("lo0");
    Assumptions.assumeTrue(lo0 != null, "no lo0 interface on this OS — direct unicast test covers fallback");
    MulticastConfig cfg = MulticastConfig.defaults().withInterface("lo0");
    MulticastSupport.ProbeResult probe = MulticastSupport.probe(cfg);
    Assumptions.assumeTrue(!probe.available(),
        "lo0 actually delivers multicast on this OS — fallback not triggerable here; probe: " + probe.detail());
    System.out.println("[UnicastFallbackTest] lo0 probe correctly reports UNAVAILABLE: " + probe.detail());

    LabKeytool.LabKeystores ks = LabKeytool.generate(certDir, LabDefaults.SERVER_ID);
    SSLContext serverCtx = LabTlsContexts.server(ks.serverKeystore(), ks.password());
    SSLContext clientCtx = LabTlsContexts.client(ks.trustKeystore(), ks.password());
    AlertDirectory directory = new AlertDirectory();
    directory.put(new AlertContent("fallback-alert", 2, "Fallback alert",
        "Delivered via unicast fallback, verified over TLS.", "MEDIUM", Instant.now().toString()));

    try (LabTlsServer tls = new LabTlsServer(serverCtx, LabDefaults.SERVER_ID, directory)) {
      int tlsPort = tls.start(0);
      List<VerifiedAlertSubscriber.VerifiedDelivery> delivered = new CopyOnWriteArrayList<>();
      List<LabTlsClient> clients = new CopyOnWriteArrayList<>();
      try (FallbackHintBus bus = new FallbackHintBus(cfg, 2, i -> {
        try {
          LabTlsClient c = LabTlsClient.connectVerified("127.0.0.1", tlsPort, clientCtx,
              LabDefaults.SERVER_ID, "0123456789abcdef0123456789abcdef");
          clients.add(c);
          return new VerifiedAlertSubscriber(c, delivered::add);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      })) {
        assertEquals(FallbackHintBus.Mode.UNICAST_FALLBACK, bus.mode());
        bus.publish(new AlertHint("fallback-alert", 2, LabDefaults.SERVER_ID));
        MulticastLoopbackTest.await(() -> delivered.size() == 2, 8000);
        assertEquals(2, delivered.size());
        for (var d : delivered) {
          assertEquals(2, d.alert().revision());
          assertEquals("Fallback alert", d.alert().title());
        }
        assertEquals(2, bus.publisher().packetsSent(), "fallback places N copies on the link");
        for (LabTlsClient c : clients) { try { c.close(); } catch (Exception ignore) { } }
      }
    }
  }
}
