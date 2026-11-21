package aegis.networklab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.MessageType;
import aegis.protocol.Protocol;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The trust gate: a UDP discovery reply is NEVER trusted before TLS verification.
 * Real TLS with keytool-generated lab certs (pinned trust store), real EBSP frames.
 */
class DiscoveryTrustTest {

  @TempDir
  static Path certDir;

  private static SSLContext serverCtx;   // CN=aegis-lab-server
  private static SSLContext clientCtx;   // pinned to aegis-lab-server only
  private static SSLContext rogueCtx;    // CN=rogue-server (NOT in client trust store)
  private static final String SERVER_ID = "aegis-lab-server";

  @BeforeAll
  static void generateCerts() throws Exception {
    LabKeytool.LabKeystores lab = LabKeytool.generate(certDir.resolve("lab"), SERVER_ID);
    LabKeytool.LabKeystores rogue = LabKeytool.generate(certDir.resolve("rogue"), "rogue-server");
    serverCtx = LabTlsContexts.server(lab.serverKeystore(), lab.password());
    clientCtx = LabTlsContexts.client(lab.trustKeystore(), lab.password());
    rogueCtx = LabTlsContexts.server(rogue.serverKeystore(), rogue.password());
  }

  private static AlertDirectory seeded() {
    AlertDirectory d = new AlertDirectory();
    d.put(new AlertContent("alert-1", 5, "Authoritative title", "Authoritative body", "HIGH",
        Instant.now().toString()));
    return d;
  }

  @Test
  void happyPathDiscoveryThenTlsVerificationThenFetch() throws Exception {
    try (LabTlsServer tls = new LabTlsServer(serverCtx, SERVER_ID, seeded())) {
      int tlsPort = tls.start(0);
      try (DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
          0, "127.0.0.1", tlsPort, SERVER_ID))) {
        discovery.start();
        DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
            "127.0.0.1", discovery.port(), 3000, SERVER_ID));
        DiscoveryClient.TrustedServer trusted = client.discoverTrusted(clientCtx);
        assertEquals("127.0.0.1", trusted.serverHost());
        assertEquals(tlsPort, trusted.tlsPort());
        assertEquals(SERVER_ID, trusted.serverId());

        // content flows ONLY over the verified TLS session
        try (LabTlsClient fetch = LabTlsClient.connectVerified(
            trusted.serverHost(), trusted.tlsPort(), clientCtx, trusted.serverId(), trusted.nonce())) {
          Optional<AlertContent> content = fetch.fetchAlert("alert-1");
          assertTrue(content.isPresent());
          assertEquals(5, content.get().revision());
          assertEquals("Authoritative title", content.get().title());
          assertTrue(fetch.fetchAlert("no-such-alert").isEmpty());
        }
      }
    }
  }

  @Test
  void udpReplyAloneIsUntrustedPointer() throws Exception {
    try (LabTlsServer tls = new LabTlsServer(serverCtx, SERVER_ID, seeded())) {
      int tlsPort = tls.start(0);
      try (DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
          0, "127.0.0.1", tlsPort, SERVER_ID))) {
        discovery.start();
        DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
            "127.0.0.1", discovery.port(), 3000, null));
        DiscoveryClient.UntrustedEndpoint u = client.discoverUntrusted();
        // The API type itself marks it untrusted: no fetch/display is possible from it.
        assertEquals(tlsPort, u.tlsPort());
        assertNotNull(u.nonce());
      }
    }
  }

  @Test
  void unpinnedCertificateIsRejected() throws Exception {
    // rogue TLS endpoint (cert not in pinned trust store) advertised via discovery
    try (LabTlsServer rogueTls = new LabTlsServer(rogueCtx, "rogue-server", seeded())) {
      int tlsPort = rogueTls.start(0);
      try (DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
          0, "127.0.0.1", tlsPort, "rogue-server"))) {
        discovery.start();
        DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
            "127.0.0.1", discovery.port(), 3000, null));
        DiscoveryRejectedException ex = assertThrows(DiscoveryRejectedException.class,
            () -> client.discoverTrusted(clientCtx));
        assertTrue(ex.getMessage().contains("TLS"), ex.getMessage());
      }
    }
  }

  @Test
  void certificateCnMustEqualAdvertisedServerId() throws Exception {
    // discovery claims serverId "impostor" but the (pinned, valid) cert CN is aegis-lab-server
    try (LabTlsServer tls = new LabTlsServer(serverCtx, SERVER_ID, seeded())) {
      int tlsPort = tls.start(0);
      try (DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
          0, "127.0.0.1", tlsPort, "impostor"))) {
        discovery.start();
        DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
            "127.0.0.1", discovery.port(), 3000, null));
        DiscoveryRejectedException ex = assertThrows(DiscoveryRejectedException.class,
            () -> client.discoverTrusted(clientCtx));
        assertTrue(ex.getMessage().contains("CN") || ex.getMessage().contains("serverId"), ex.getMessage());
      }
    }
  }

  @Test
  void serverHelloMustEchoDiscoveryNonceOverTls() throws Exception {
    // misbehaving TLS server: valid pinned cert, but SERVER_HELLO echoes a WRONG nonce
    ObjectMapper mapper = new ObjectMapper();
    FrameCodec codec = new FrameCodecImpl(mapper);
    try (SSLServerSocket bad = (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0)) {
      int badPort = bad.getLocalPort();
      Thread t = new Thread(() -> {
        try (SSLSocket s = (SSLSocket) bad.accept()) {
          Envelope hello = codec.readFrame(s.getInputStream());
          ObjectNode payload = mapper.createObjectNode();
          payload.put("serverId", SERVER_ID);
          payload.put("nonce", "deadbeefdeadbeefdeadbeefdeadbeef"); // NOT the discovery nonce
          payload.put("version", Protocol.VERSION);
          codec.writeFrame(s.getOutputStream(), new Envelope(Protocol.VERSION,
              MessageType.SERVER_HELLO.name(), UUID.randomUUID().toString(), hello.messageId(),
              Instant.now().toString(), payload));
        } catch (Exception ignore) { }
      });
      t.setDaemon(true);
      t.start();
      try (DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
          0, "127.0.0.1", badPort, SERVER_ID))) {
        discovery.start();
        DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
            "127.0.0.1", discovery.port(), 3000, null));
        DiscoveryRejectedException ex = assertThrows(DiscoveryRejectedException.class,
            () -> client.discoverTrusted(clientCtx));
        assertTrue(ex.getMessage().contains("nonce"), ex.getMessage());
      }
    }
  }

  @Test
  void certIdentityParsesCn() {
    assertEquals("aegis-lab-server",
        CertIdentity.cnFromRfc2253("CN=aegis-lab-server,OU=AEGIS Network Lab,O=CMP180"));
    assertEquals("weird,name", CertIdentity.cnFromRfc2253("CN=weird\\,name,O=X"));
    assertNull(CertIdentity.cnFromRfc2253("OU=no-cn,O=X"));
  }
}
