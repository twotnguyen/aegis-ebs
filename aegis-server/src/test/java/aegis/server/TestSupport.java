package aegis.server;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared test helpers: loopback socket pairs, keytool keystores, polling awaits. */
final class TestSupport {

  private TestSupport() {}

  record SocketPair(Socket sessionSide, Socket peerSide) implements AutoCloseable {
    @Override public void close() {
      try { sessionSide.close(); } catch (Exception ignored) { }
      try { peerSide.close(); } catch (Exception ignored) { }
    }
  }

  /** Connected loopback pair: sessionSide plays the server, peerSide plays the client. */
  static SocketPair socketPair() throws Exception {
    try (ServerSocket ss = new ServerSocket(0)) {
      Socket peer = new Socket("localhost", ss.getLocalPort());
      Socket session = ss.accept();
      peer.setTcpNoDelay(true);
      session.setTcpNoDelay(true);
      return new SocketPair(session, peer);
    }
  }

  /** Generates a self-signed PKCS12 keystore with keytool (CN=localhost, SAN loopback). */
  static Path generateKeystore(Path dir) throws Exception {
    Path ks = dir.resolve("server.p12");
    Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "aegis",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        "-keystore", ks.toString(), "-storetype", "PKCS12",
        "-storepass", "changeit", "-keypass", "changeit")
        .redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes());
    assertTrue(p.waitFor(90, TimeUnit.SECONDS), "keytool timed out");
    assertEquals(0, p.exitValue(), "keytool failed: " + output);
    return ks;
  }

  /** TEST-ONLY trust-all context (self-signed loopback cert). */
  static SSLContext trustAllContext() throws Exception {
    TrustManager[] tm = {new X509ExtendedTrustManager() {
      @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
      @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
      @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }
      @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }
      @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
      @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
      @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }};
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tm, new SecureRandom());
    return ctx;
  }

  /** Polls until the condition holds or fails the test with a descriptive message. */
  static void until(String what, BooleanSupplier condition, long timeoutMs) {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      if (condition.getAsBoolean()) return;
      sleep(25);
    }
    fail("timed out after " + timeoutMs + "ms waiting for: " + what);
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
