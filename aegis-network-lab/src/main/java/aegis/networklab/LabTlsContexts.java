package aegis.networklab;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/** SSLContext factories for the lab PKCS12 keystores (server key pair / pinned client trust). */
public final class LabTlsContexts {
  private LabTlsContexts() {}

  public static SSLContext server(Path keystore, String password) throws Exception {
    KeyStore ks = load(keystore, password);
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, password.toCharArray());
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    return ctx;
  }

  public static SSLContext client(Path truststore, String password) throws Exception {
    KeyStore ts = load(truststore, password);
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ts);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    return ctx;
  }

  private static KeyStore load(Path p, String password) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(p)) {
      ks.load(in, password.toCharArray());
    }
    return ks;
  }
}
