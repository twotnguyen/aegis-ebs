package aegis.networklab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a SELF-SIGNED lab certificate + pinned trust store with the JDK's keytool.
 * LAB/TRAINING ONLY — the password is a public constant, never use this for real deployments.
 * Cert: RSA 2048, CN=serverId, SAN=dns:localhost,ip:127.0.0.1 (so HTTPS endpoint identification
 * passes for loopback connections).
 */
public final class LabKeytool {

  public static final String LAB_PASSWORD = "aegis-lab"; // public lab fixture, NOT a secret

  public record LabKeystores(Path serverKeystore, Path trustKeystore, String password) {}

  private LabKeytool() {}

  public static LabKeystores generate(Path dir, String serverId) throws IOException, InterruptedException {
    Files.createDirectories(dir);
    Path server = dir.resolve("lab-server.p12");
    Path cert = dir.resolve("lab-server.crt");
    Path trust = dir.resolve("lab-trust.p12");
    Files.deleteIfExists(server);
    Files.deleteIfExists(cert);
    Files.deleteIfExists(trust);
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    run(List.of(keytool, "-genkeypair",
        "-alias", "aegis-lab", "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
        "-dname", "CN=" + serverId + ", OU=AEGIS Network Lab, O=CMP180",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        "-validity", "3650", "-keystore", server.toString(), "-storetype", "PKCS12",
        "-storepass", LAB_PASSWORD, "-keypass", LAB_PASSWORD));
    run(List.of(keytool, "-exportcert", "-alias", "aegis-lab",
        "-keystore", server.toString(), "-storepass", LAB_PASSWORD,
        "-rfc", "-file", cert.toString()));
    run(List.of(keytool, "-importcert", "-alias", "aegis-lab",
        "-file", cert.toString(), "-keystore", trust.toString(), "-storetype", "PKCS12",
        "-storepass", LAB_PASSWORD, "-noprompt"));
    return new LabKeystores(server, trust, LAB_PASSWORD);
  }

  private static void run(List<String> cmd) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int code = p.waitFor();
    if (code != 0)
      throw new IOException("keytool failed (exit " + code + "): " + String.join(" ", cmd) + "\n" + out);
  }
}
