package aegis.networklab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Advisory-hint security semantics: hints are triggers, never content. A forged hint with a
 * lying revision must still result in the AUTHORITATIVE TLS revision at the sink; stale and
 * duplicate hints are ignored without extra fetches.
 */
class VerifiedAlertSubscriberTest {

  @TempDir
  Path certDir;

  @Test
  void forgedAndStaleHintsNeverBypassTlsTruth() throws Exception {
    LabKeytool.LabKeystores ks = LabKeytool.generate(certDir, LabDefaults.SERVER_ID);
    SSLContext serverCtx = LabTlsContexts.server(ks.serverKeystore(), ks.password());
    SSLContext clientCtx = LabTlsContexts.client(ks.trustKeystore(), ks.password());
    AlertDirectory directory = new AlertDirectory();
    directory.put(new AlertContent("a1", 2, "Real title rev2", "real body", "HIGH", Instant.now().toString()));

    try (LabTlsServer tls = new LabTlsServer(serverCtx, LabDefaults.SERVER_ID, directory)) {
      int tlsPort = tls.start(0);
      try (LabTlsClient client = LabTlsClient.connect("127.0.0.1", tlsPort, clientCtx)) {
        List<VerifiedAlertSubscriber.VerifiedDelivery> sink = new CopyOnWriteArrayList<>();
        VerifiedAlertSubscriber sub = new VerifiedAlertSubscriber(client, sink::add);

        // forged hint claims revision 999 — server truth is rev 2
        sub.onHint(new AlertHint("a1", 999, "anyone-can-spoof"));
        assertEquals(1, sink.size());
        assertEquals(2, sink.get(0).alert().revision(), "sink must show TLS revision, not the hint's lie");
        assertEquals("Real title rev2", sink.get(0).alert().title());
        assertEquals(1, sub.fetchesIssued());

        // duplicate + stale hints: no fetch, no redelivery
        sub.onHint(new AlertHint("a1", 999, "spoofer"));
        sub.onHint(new AlertHint("a1", 2, "spoofer"));
        sub.onHint(new AlertHint("a1", 1, "spoofer"));
        assertEquals(1, sink.size());
        assertEquals(1, sub.fetchesIssued());
        assertEquals(3, sub.staleIgnored());

        // server publishes rev 3; a truthful hint now delivers the new revision
        directory.put(new AlertContent("a1", 3, "Real title rev3", "new body", "HIGH", Instant.now().toString()));
        sub.onHint(new AlertHint("a1", 3, LabDefaults.SERVER_ID));
        assertEquals(2, sink.size());
        assertEquals(3, sink.get(1).alert().revision());

        // hint for an alert the server doesn't know: fetch miss, nothing displayed
        sub.onHint(new AlertHint("ghost-alert", 1, "spoofer"));
        assertEquals(2, sink.size());
        assertEquals(1, sub.fetchMisses());
        assertEquals(0, sub.fetchFailures());
        assertEquals(6, sub.hintsReceived());
      }
    }
  }

  @Test
  void hintOverRealUnicastTransportTriggersVerifiedFetch() throws Exception {
    LabKeytool.LabKeystores ks = LabKeytool.generate(certDir, LabDefaults.SERVER_ID);
    SSLContext serverCtx = LabTlsContexts.server(ks.serverKeystore(), ks.password());
    SSLContext clientCtx = LabTlsContexts.client(ks.trustKeystore(), ks.password());
    AlertDirectory directory = new AlertDirectory();
    directory.put(new AlertContent("t1", 1, "Transport alert", "body", "LOW", Instant.now().toString()));
    try (LabTlsServer tls = new LabTlsServer(serverCtx, LabDefaults.SERVER_ID, directory)) {
      int tlsPort = tls.start(0);
      try (LabTlsClient client = LabTlsClient.connect("127.0.0.1", tlsPort, clientCtx)) {
        List<VerifiedAlertSubscriber.VerifiedDelivery> sink = new CopyOnWriteArrayList<>();
        VerifiedAlertSubscriber vas = new VerifiedAlertSubscriber(client, sink::add);
        try (UnicastHintPublisher pub = new UnicastHintPublisher();
             UnicastHintSubscriber sub = new UnicastHintSubscriber(vas)) {
          pub.addTarget(sub.unicastEndpoint());
          pub.publish(new AlertHint("t1", 1, LabDefaults.SERVER_ID));
          MulticastLoopbackTest.await(() -> sink.size() == 1, 5000);
          assertEquals(1, sink.size());
          assertEquals("Transport alert", sink.get(0).alert().title());
          // duplicate datagram (UDP can duplicate) → no redelivery
          pub.publish(new AlertHint("t1", 1, LabDefaults.SERVER_ID));
          MulticastLoopbackTest.await(() -> sub.hintsReceived() == 2, 5000);
          Thread.sleep(150);
          assertEquals(1, sink.size(), "duplicate hint must not redisplay content");
          assertEquals(1, vas.staleIgnored());
        }
      }
    }
  }
}
