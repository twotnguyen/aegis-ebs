package aegis.networklab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration: REAL multicast loopback on 239.10.10.10:9998 when the environment delivers it
 * (verified by a real send/receive probe). If the environment does NOT support multicast
 * (e.g. forced lo0 on macOS), the test prints the exact reason and exercises the unicast
 * fallback instead — never silently skipped.
 */
class MulticastLoopbackTest {

  @TempDir
  Path certDir;

  @Test
  void multicastHintThenVerifiedTlsFetch() throws Exception {
    MulticastConfig cfg = MulticastConfig.defaults(); // 239.10.10.10:9998 ttl=1 default iface
    MulticastSupport.ProbeResult probe = MulticastSupport.probe(cfg);

    LabKeytool.LabKeystores ks = LabKeytool.generate(certDir, LabDefaults.SERVER_ID);
    SSLContext serverCtx = LabTlsContexts.server(ks.serverKeystore(), ks.password());
    SSLContext clientCtx = LabTlsContexts.client(ks.trustKeystore(), ks.password());
    AlertDirectory directory = new AlertDirectory();
    directory.put(new AlertContent("mcast-alert", 3, "Multicast-triggered alert",
        "Authoritative content fetched over TLS after an advisory hint.", "HIGH", Instant.now().toString()));

    try (LabTlsServer tls = new LabTlsServer(serverCtx, LabDefaults.SERVER_ID, directory)) {
      int tlsPort = tls.start(0);
      List<VerifiedAlertSubscriber.VerifiedDelivery> delivered = new CopyOnWriteArrayList<>();
      List<LabTlsClient> clients = new CopyOnWriteArrayList<>();
      FallbackHintBus bus = new FallbackHintBus(cfg, 3, i -> {
        try {
          LabTlsClient c = LabTlsClient.connectVerified("127.0.0.1", tlsPort, clientCtx,
              LabDefaults.SERVER_ID, "0123456789abcdef0123456789abcdef");
          clients.add(c);
          return new VerifiedAlertSubscriber(c, delivered::add);
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      });
      try {
        if (probe.available()) {
          assertEquals(FallbackHintBus.Mode.MULTICAST, bus.mode());
          System.out.println("[MulticastLoopbackTest] REAL MULTICAST loopback verified: " + probe.detail());
        } else {
          assertEquals(FallbackHintBus.Mode.UNICAST_FALLBACK, bus.mode());
          System.out.println("[MulticastLoopbackTest] MULTICAST UNSUPPORTED in this environment: "
              + probe.detail() + " — unicast fallback exercised instead (task-sanctioned).");
        }
        Thread.sleep(150); // let joins settle
        bus.publish(new AlertHint("mcast-alert", 3, LabDefaults.SERVER_ID));
        await(() -> delivered.size() == 3, 10_000);
        assertEquals(3, delivered.size(), "every subscriber must fetch verified content after the hint");
        for (var d : delivered) {
          assertEquals("mcast-alert", d.alert().alertId());
          assertEquals(3, d.alert().revision(), "revision comes from TLS content, not the hint");
          assertEquals("Multicast-triggered alert", d.alert().title());
        }
        for (HintSubscriber s : bus.subscribers()) assertTrue(s.hintsReceived() >= 1);
        long expectedEgress = probe.available() ? 1 : 3;
        assertEquals(expectedEgress, bus.publisher().packetsSent(),
            "multicast puts ONE copy on the source link; unicast fallback puts N");
      } finally {
        bus.close();
        for (LabTlsClient c : clients) { try { c.close(); } catch (Exception ignore) { } }
      }
    }
  }

  static void await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
  }
}
