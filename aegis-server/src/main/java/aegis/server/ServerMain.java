package aegis.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * CLI entry point.
 *
 * <pre>
 * scripts/gen-server-cert.sh                       # once: self-signed identity
 * mvn -q -DskipTests package
 * java -cp aegis-server/target/classes:$(cat cp.txt) aegis.server.ServerMain \
 *     --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
 * </pre>
 *
 * Options: --db --keystore --storepass --tls-port --ws-port --building
 *          --devices-per-zone --no-seed --heartbeat-ms --retry-base-ms
 */
public final class ServerMain {

  public static void main(String[] args) throws Exception {
    Path db = Path.of("aegis.db");
    Path keystore = Path.of("certs/aegis-server.p12");
    String storepass = "aegis-demo";
    ServerConfig.Builder builder = null;
    int tlsPort = 9443;
    int wsPort = 9444;
    String building = "AEGIS-BLD-A";
    int devicesPerZone = 12;
    boolean seed = true;
    long heartbeatMs = 10_000;
    long retryBaseMs = 3_000;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--db" -> db = Path.of(args[++i]);
        case "--keystore" -> keystore = Path.of(args[++i]);
        case "--storepass" -> storepass = args[++i];
        case "--tls-port" -> tlsPort = Integer.parseInt(args[++i]);
        case "--ws-port" -> wsPort = Integer.parseInt(args[++i]);
        case "--building" -> building = args[++i];
        case "--devices-per-zone" -> devicesPerZone = Integer.parseInt(args[++i]);
        case "--no-seed" -> seed = false;
        case "--heartbeat-ms" -> heartbeatMs = Long.parseLong(args[++i]);
        case "--retry-base-ms" -> retryBaseMs = Long.parseLong(args[++i]);
        default -> {
          System.err.println("unknown option: " + args[i]);
          System.exit(2);
        }
      }
    }
    if (!Files.exists(keystore)) {
      System.err.println("keystore not found: " + keystore);
      System.err.println("generate it first: scripts/gen-server-cert.sh");
      System.exit(1);
    }

    ServerConfig config = ServerConfig.builder(db, keystore, storepass)
        .tlsPort(tlsPort).wsPort(wsPort).buildingId(building)
        .devicesPerZone(devicesPerZone).seedDemo(seed)
        .heartbeat(heartbeatMs, 3).retry(retryBaseMs, 3)
        .build();

    AegisServer server = new AegisServer(config);
    CountDownLatch stop = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.close();
      stop.countDown();
    }, "aegis-shutdown"));
    server.start();

    System.out.println("AEGIS EBS server ready");
    System.out.println("  EBSP/2.0 TLS : localhost:" + server.tlsPort());
    System.out.println("  status feed  : ws://localhost:" + server.wsPort() + "/ (status/1)");
    System.out.println("  database     : " + db.toAbsolutePath());
    if (seed) {
      System.out.println("  demo operators: admin/admin-demo-secret (SYSTEM_ADMIN),");
      System.out.println("    secops/secops-demo-secret (building), floor7/floor7-demo-secret (floor 7)");
      System.out.println("  demo devices: DEV-A-F01-A-01.. (credential device-demo-secret)");
    }
    stop.await();
  }

  private ServerMain() {}
}
