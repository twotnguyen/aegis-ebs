package aegis.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;

import static aegis.client.TestServer.alertPayload;
import static aegis.client.TestUtil.await;
import static org.junit.jupiter.api.Assertions.*;

/** TLS path: truststore-verified loopback handshake + durable alert + ACK_RECEIVED. */
@Timeout(90)
class ClientTlsSmokeTest {

  @TempDir Path tmp;

  @Test void tlsHandshakeAlertAndAckOverLoopback() throws Exception {
    TestUtil.TlsPair tls = TestUtil.generateTls(tmp);
    SSLContext serverCtx = TlsSupport.serverContext(tls.keystore(), tls.password());
    TestServer server = new TestServer(TestServer.pushScript(
        List.of(alertPayload("ALT-TLS", 1, "DLV-TLS", "SIMULATION — TLS drill")), List.of(), false), serverCtx);
    SSLContext clientCtx = TlsSupport.clientContext(tls.truststore(), tls.password());

    ClientConfig cfg = new ClientConfig("127.0.0.1", server.port(), "DEV-TLS-01", "USER-TLS-01",
        "simulation-secret", tls.truststore(), tls.password(), tmp.resolve("inbox.db"), false,
        3, 20, 10_000);
    InboxStore inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    ClientEngineTest.RecordingListener listener = new ClientEngineTest.RecordingListener();
    AlertClientEngine engine = new AlertClientEngine(cfg, inbox,
        (host, port) -> clientCtx.getSocketFactory().createSocket(host, port),
        Thread::sleep, Clock.systemUTC(), new Random(7), listener);
    try {
      engine.start();
      await("ACK_RECEIVED over TLS", 20_000, () -> !server.framesOfType("ACK_RECEIVED").isEmpty());
      assertTrue(server.sawTlsSocket, "server side must be an SSLSocket");
      assertEquals("ALT-TLS", server.framesOfType("ACK_RECEIVED").get(0).payload().get("alertId").asText());
      assertEquals(1, listener.fresh.size());
      assertTrue(inbox.find("ALT-TLS").isPresent());
      assertEquals(AlertClientEngine.State.ONLINE, engine.state());
    } finally {
      engine.close();
      inbox.close();
      server.close();
    }
  }

  @Test void untrustedServerCertificateIsRejected() throws Exception {
    TestUtil.TlsPair serverTls = TestUtil.generateTls(tmp.resolve("server"));
    TestUtil.TlsPair otherTls = TestUtil.generateTls(tmp.resolve("other")); // different CA/cert
    SSLContext serverCtx = TlsSupport.serverContext(serverTls.keystore(), serverTls.password());
    TestServer server = new TestServer(TestServer.pushScript(List.of(), List.of(), false), serverCtx);
    SSLContext wrongTrust = TlsSupport.clientContext(otherTls.truststore(), otherTls.password());

    ClientConfig cfg = new ClientConfig("127.0.0.1", server.port(), "DEV-TLS-02", "USER-TLS-02",
        "simulation-secret", otherTls.truststore(), otherTls.password(), tmp.resolve("inbox2.db"), false,
        2, 10, 10_000);
    InboxStore inbox = new InboxStore(cfg.inboxDb(), cfg.deviceId());
    ClientEngineTest.RecordingListener listener = new ClientEngineTest.RecordingListener();
    AlertClientEngine engine = new AlertClientEngine(cfg, inbox,
        (host, port) -> wrongTrust.getSocketFactory().createSocket(host, port),
        Thread::sleep, Clock.systemUTC(), new Random(7), listener);
    try {
      engine.start();
      await("TLS verification failure exhausts retries", 20_000,
          () -> engine.state() == AlertClientEngine.State.FAILED);
      assertTrue(listener.fresh.isEmpty(), "no alert may be accepted from an untrusted server");
      assertTrue(server.framesOfType("ACK_RECEIVED").isEmpty());
    } finally {
      engine.close();
      inbox.close();
      server.close();
    }
  }
}
