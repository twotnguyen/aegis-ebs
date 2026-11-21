package aegis.simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** keytool-generated TLS fixtures for loopback smoke tests. */
final class SimTestUtil {

  private SimTestUtil() {}

  record TlsPair(Path keystore, Path truststore, String password) {}

  static TlsPair generateTls(Path dir) throws Exception {
    Files.createDirectories(dir);
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Path ks = dir.resolve("server.p12");
    Path ts = dir.resolve("truststore.p12");
    Path cert = dir.resolve("server.crt");
    run(keytool, "-genkeypair", "-alias", "aegis-sim", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "1", "-keystore", ks.toString(), "-storetype", "PKCS12",
        "-storepass", "changeit", "-dname", "CN=localhost");
    run(keytool, "-exportcert", "-rfc", "-alias", "aegis-sim", "-keystore", ks.toString(),
        "-storepass", "changeit", "-file", cert.toString());
    run(keytool, "-importcert", "-noprompt", "-alias", "aegis-sim", "-file", cert.toString(),
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
