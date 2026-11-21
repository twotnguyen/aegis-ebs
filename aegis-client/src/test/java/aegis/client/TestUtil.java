package aegis.client;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Test helpers: polling await + keytool-generated TLS fixtures. */
final class TestUtil {

  private TestUtil() {}

  static void await(String what, long timeoutMs, BooleanSupplier cond) {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      if (cond.getAsBoolean()) return;
      try { Thread.sleep(20); }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new AssertionError("interrupted", ie); }
    }
    throw new AssertionError("timed out after " + timeoutMs + "ms waiting for: " + what);
  }

  record TlsPair(Path keystore, Path truststore, String password) {}

  /** Self-signed localhost cert: server keystore + client truststore (PKCS12). */
  static TlsPair generateTls(Path dir) throws Exception {
    java.nio.file.Files.createDirectories(dir);
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Path ks = dir.resolve("server.p12");
    Path ts = dir.resolve("truststore.p12");
    Path cert = dir.resolve("server.crt");
    run(keytool, "-genkeypair", "-alias", "aegis-test", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "1", "-keystore", ks.toString(), "-storetype", "PKCS12",
        "-storepass", "changeit", "-dname", "CN=localhost");
    run(keytool, "-exportcert", "-rfc", "-alias", "aegis-test", "-keystore", ks.toString(),
        "-storepass", "changeit", "-file", cert.toString());
    run(keytool, "-importcert", "-noprompt", "-alias", "aegis-test", "-file", cert.toString(),
        "-keystore", ts.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
    return new TlsPair(ks, ts, "changeit");
  }

  private static void run(String... cmd) throws Exception {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("keytool timed out"); }
    if (p.exitValue() != 0) throw new IllegalStateException("keytool failed (" + p.exitValue() + "): " + out);
  }
}
