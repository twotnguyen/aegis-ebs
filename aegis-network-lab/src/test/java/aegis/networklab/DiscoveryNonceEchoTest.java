package aegis.networklab;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** UDP discovery on loopback: nonce echo, rogue-reply rejection, timeout, garbage drop. */
class DiscoveryNonceEchoTest {

  private static final String NONCE = "0123456789abcdef0123456789abcdef";
  private final LabDatagramCodec codec = new LabDatagramCodec();

  @Test
  void serverEchoesNonceVerbatim() throws Exception {
    try (DiscoveryServer server = new DiscoveryServer(new DiscoveryServer.Config(
        0, "127.0.0.1", 9443, "aegis-lab-server"))) {
      server.start();
      try (DatagramSocket client = new DatagramSocket()) {
        client.setSoTimeout(3000);
        byte[] req = codec.encode(new DiscoverRequest(NONCE, 2));
        client.send(new DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), server.port()));
        DatagramPacket reply = new DatagramPacket(new byte[1024], 1024);
        client.receive(reply);
        byte[] data = new byte[reply.getLength()];
        System.arraycopy(reply.getData(), 0, data, 0, reply.getLength());
        DiscoverResponse r = codec.decodeResponse(data);
        assertEquals(NONCE, r.nonce(), "server must echo the request nonce exactly");
        assertEquals("127.0.0.1", r.serverHost());
        assertEquals(9443, r.tlsPort());
        assertEquals("aegis-lab-server", r.serverId());
      }
      assertEquals(1, server.repliesSent());
      assertEquals(0, server.invalidDropped());
    }
  }

  @Test
  void clientAcceptsMatchingNonce() throws Exception {
    try (DiscoveryServer server = new DiscoveryServer(new DiscoveryServer.Config(
        0, "127.0.0.1", 9443, "aegis-lab-server"))) {
      server.start();
      DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
          "127.0.0.1", server.port(), 3000, "aegis-lab-server"));
      DiscoveryClient.UntrustedEndpoint u = client.discoverUntrusted(NONCE);
      assertEquals(NONCE, u.nonce());
      assertEquals(9443, u.tlsPort());
      // stage 1 is explicitly UNTRUSTED: it is only a pointer, no content, no TLS contacted yet
      assertEquals("aegis-lab-server", u.serverId());
    }
  }

  @Test
  void rogueReplyWithWrongNonceIsRejected() throws Exception {
    try (DatagramSocket rogue = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
      Thread t = new Thread(() -> {
        try {
          DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
          rogue.receive(p);
          byte[] fake = codec.encode(new DiscoverResponse(
              "127.0.0.1", 9443, "ffffffffffffffffffffffffffffffff", "rogue-server"));
          rogue.send(new DatagramPacket(fake, fake.length, p.getAddress(), p.getPort()));
        } catch (Exception ignore) { }
      });
      t.setDaemon(true);
      t.start();
      DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
          "127.0.0.1", rogue.getLocalPort(), 3000, null));
      DiscoveryRejectedException ex = assertThrows(DiscoveryRejectedException.class,
          () -> client.discoverUntrusted(NONCE));
      assertTrue(ex.getMessage().contains("nonce"), ex.getMessage());
    }
  }

  @Test
  void unexpectedServerIdIsRejected() throws Exception {
    try (DiscoveryServer server = new DiscoveryServer(new DiscoveryServer.Config(
        0, "127.0.0.1", 9443, "someone-else"))) {
      server.start();
      DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
          "127.0.0.1", server.port(), 3000, "aegis-lab-server"));
      DiscoveryRejectedException ex = assertThrows(DiscoveryRejectedException.class,
          () -> client.discoverUntrusted(NONCE));
      assertTrue(ex.getMessage().contains("serverId"), ex.getMessage());
    }
  }

  @Test
  void timeoutWhenNoServerAnswers() throws Exception {
    try (DatagramSocket silent = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
      DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
          "127.0.0.1", silent.getLocalPort(), 300, null));
      long t0 = System.nanoTime();
      assertThrows(DiscoveryRejectedException.class, () -> client.discoverUntrusted(NONCE));
      long ms = (System.nanoTime() - t0) / 1_000_000;
      assertTrue(ms < 2000, "should fail fast at the configured timeout, took " + ms + "ms");
    }
  }

  @Test
  void garbageDatagramsAreDroppedNotAnswered() throws Exception {
    try (DiscoveryServer server = new DiscoveryServer(new DiscoveryServer.Config(
        0, "127.0.0.1", 9443, "aegis-lab-server"))) {
      server.start();
      try (DatagramSocket client = new DatagramSocket()) {
        client.setSoTimeout(3000);
        byte[] garbage = "this is not json".getBytes(StandardCharsets.UTF_8);
        client.send(new DatagramPacket(garbage, garbage.length, InetAddress.getByName("127.0.0.1"), server.port()));
        byte[] wrongType = codec.encode(new AlertHint("a", 1, "s"));
        client.send(new DatagramPacket(wrongType, wrongType.length, InetAddress.getByName("127.0.0.1"), server.port()));
        byte[] req = codec.encode(new DiscoverRequest(NONCE, 2));
        client.send(new DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), server.port()));
        DatagramPacket reply = new DatagramPacket(new byte[1024], 1024);
        client.receive(reply); // first reply must be for the valid request
        byte[] data = new byte[reply.getLength()];
        System.arraycopy(reply.getData(), 0, data, 0, reply.getLength());
        assertEquals(NONCE, codec.decodeResponse(data).nonce());
      }
      assertEquals(2, server.invalidDropped());
      assertEquals(1, server.repliesSent());
    }
  }
}
