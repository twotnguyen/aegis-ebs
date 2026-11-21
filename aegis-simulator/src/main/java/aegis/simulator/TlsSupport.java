package aegis.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** TLS helpers: client SSLContext from a truststore (server cert verification). */
public final class TlsSupport {

  private TlsSupport() {}

  /** SSLContext trusting exactly the certificates in the given truststore. */
  public static SSLContext clientContext(Path truststore, String password) throws IOException {
    try {
      KeyStore ts = KeyStore.getInstance(storeType(truststore));
      try (InputStream in = Files.newInputStream(truststore)) {
        ts.load(in, password == null ? null : password.toCharArray());
      }
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ts);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
      return ctx;
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException("cannot load truststore " + truststore, ex);
    }
  }

  /** SSLContext presenting the given keystore (used by test fixture servers). */
  public static SSLContext serverContext(Path keystore, String password) throws IOException {
    try {
      KeyStore ks = KeyStore.getInstance(storeType(keystore));
      try (InputStream in = Files.newInputStream(keystore)) {
        ks.load(in, password == null ? null : password.toCharArray());
      }
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, password == null ? null : password.toCharArray());
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
      return ctx;
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException("cannot load keystore " + keystore, ex);
    }
  }

  private static String storeType(Path p) {
    String name = p.getFileName().toString().toLowerCase();
    return (name.endsWith(".jks") || name.endsWith(".keystore")) ? "JKS" : "PKCS12";
  }
}
