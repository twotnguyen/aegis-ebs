package aegis.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client configuration (SIMULATION/TRAINING). Defaults target the local TLS server
 * on 9443. CLI flags: --host --port --device-id --user-id --device-secret
 * --truststore --truststore-password --inbox --sound --retry-max --retry-base-ms
 * --heartbeat-timeout-ms.
 */
public record ClientConfig(
    String host, int port, String deviceId, String userId, String deviceSecret,
    Path truststore, String truststorePassword, Path inboxDb, boolean soundEnabled,
    int retryMaxAttempts, long retryBaseDelayMs, long heartbeatTimeoutMs) {

  public static ClientConfig defaults() {
    return new ClientConfig("localhost", 9443, "DEV-SIM-0001", "USER-SIM-0001", "simulation-secret",
        Path.of("truststore.p12"), "changeit", Path.of("aegis-client-inbox.db"), false,
        4, 3000L, 10_000L);
  }

  public ClientConfig {
    if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId required");
    if (retryMaxAttempts < 1) throw new IllegalArgumentException("retryMaxAttempts must be >= 1");
    if (retryBaseDelayMs < 0) throw new IllegalArgumentException("retryBaseDelayMs must be >= 0");
    if (heartbeatTimeoutMs < 100) throw new IllegalArgumentException("heartbeatTimeoutMs must be >= 100");
  }

  /** Parse CLI flags (unknown flag or missing value => IllegalArgumentException). */
  public static ClientConfig fromArgs(String[] args) {
    ClientConfig d = defaults();
    String host = d.host(); int port = d.port();
    String deviceId = d.deviceId(), userId = d.userId(), secret = d.deviceSecret();
    Path truststore = d.truststore(); String tsPassword = d.truststorePassword();
    Path inbox = d.inboxDb(); boolean sound = d.soundEnabled();
    int retryMax = d.retryMaxAttempts(); long retryBase = d.retryBaseDelayMs(), hbTimeout = d.heartbeatTimeoutMs();
    List<String> unknown = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
        case "--host", "--port", "--device-id", "--user-id", "--device-secret",
             "--truststore", "--truststore-password", "--inbox", "--retry-max",
             "--retry-base-ms", "--heartbeat-timeout-ms" -> {
          if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + a);
          String v = args[++i];
          switch (a) {
            case "--host" -> host = v;
            case "--port" -> port = Integer.parseInt(v);
            case "--device-id" -> deviceId = v;
            case "--user-id" -> userId = v;
            case "--device-secret" -> secret = v;
            case "--truststore" -> truststore = Path.of(v);
            case "--truststore-password" -> tsPassword = v;
            case "--inbox" -> inbox = Path.of(v);
            case "--retry-max" -> retryMax = Integer.parseInt(v);
            case "--retry-base-ms" -> retryBase = Long.parseLong(v);
            case "--heartbeat-timeout-ms" -> hbTimeout = Long.parseLong(v);
            default -> throw new IllegalStateException(a);
          }
        }
        case "--sound" -> sound = true;
        case "--no-sound" -> sound = false;
        default -> unknown.add(a);
      }
    }
    if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown flags: " + unknown);
    return new ClientConfig(host, port, deviceId, userId, secret, truststore, tsPassword, inbox,
        sound, retryMax, retryBase, hbTimeout);
  }
}
