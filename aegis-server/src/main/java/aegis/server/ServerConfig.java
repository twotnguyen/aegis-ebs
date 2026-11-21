package aegis.server;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable server configuration. Defaults follow docs/PROTOCOL.md and the
 * dashboard status contract: TLS 9443, WS 9444, heartbeat 10s / suspect after
 * 3 missed, retries 3s/6s/12s max 3, bounded writer queue 256.
 *
 * <p>Ports may be 0 (ephemeral) for tests; the actual bound ports are exposed
 * by {@link AegisServer#tlsPort()} / {@link AegisServer#wsPort()}.
 */
public record ServerConfig(
    int tlsPort,
    int wsPort,
    Path dbFile,
    Path keystore,
    String keystorePassword,
    String buildingId,
    long heartbeatIntervalMs,
    int heartbeatMaxMissed,
    long retryBaseDelayMs,
    int maxRetries,
    int writerQueueCapacity,
    long statusBatchMs,
    boolean seedDemo,
    int devicesPerZone,
    int argon2Iterations,
    int argon2MemoryKib,
    int argon2Parallelism) {

  public ServerConfig {
    Objects.requireNonNull(dbFile, "dbFile");
    Objects.requireNonNull(keystore, "keystore");
    Objects.requireNonNull(keystorePassword, "keystorePassword");
    Objects.requireNonNull(buildingId, "buildingId");
    if (heartbeatIntervalMs <= 0) throw new IllegalArgumentException("heartbeatIntervalMs > 0");
    if (heartbeatMaxMissed < 1) throw new IllegalArgumentException("heartbeatMaxMissed >= 1");
    if (retryBaseDelayMs < 0) throw new IllegalArgumentException("retryBaseDelayMs >= 0");
    if (maxRetries < 0) throw new IllegalArgumentException("maxRetries >= 0");
    if (writerQueueCapacity < 1) throw new IllegalArgumentException("writerQueueCapacity >= 1");
    if (statusBatchMs <= 0) throw new IllegalArgumentException("statusBatchMs > 0");
    if (devicesPerZone < 0) throw new IllegalArgumentException("devicesPerZone >= 0");
  }

  public static Builder builder(Path dbFile, Path keystore, String keystorePassword) {
    return new Builder(dbFile, keystore, keystorePassword);
  }

  public static final class Builder {
    private int tlsPort = 9443;
    private int wsPort = 9444;
    private final Path dbFile;
    private final Path keystore;
    private final String keystorePassword;
    private String buildingId = "AEGIS-BLD-A";
    private long heartbeatIntervalMs = 10_000;
    private int heartbeatMaxMissed = 3;
    private long retryBaseDelayMs = 3_000;
    private int maxRetries = 3;
    private int writerQueueCapacity = 256;
    private long statusBatchMs = 500;
    private boolean seedDemo = true;
    private int devicesPerZone = 12;
    private int argon2Iterations = 2;
    private int argon2MemoryKib = 16_384;
    private int argon2Parallelism = 1;

    private Builder(Path dbFile, Path keystore, String keystorePassword) {
      this.dbFile = dbFile;
      this.keystore = keystore;
      this.keystorePassword = keystorePassword;
    }

    public Builder tlsPort(int v) { this.tlsPort = v; return this; }
    public Builder wsPort(int v) { this.wsPort = v; return this; }
    public Builder buildingId(String v) { this.buildingId = v; return this; }
    public Builder heartbeat(long intervalMs, int maxMissed) {
      this.heartbeatIntervalMs = intervalMs; this.heartbeatMaxMissed = maxMissed; return this;
    }
    public Builder retry(long baseDelayMs, int maxRetries) {
      this.retryBaseDelayMs = baseDelayMs; this.maxRetries = maxRetries; return this;
    }
    public Builder writerQueueCapacity(int v) { this.writerQueueCapacity = v; return this; }
    public Builder statusBatchMs(long v) { this.statusBatchMs = v; return this; }
    public Builder seedDemo(boolean v) { this.seedDemo = v; return this; }
    public Builder devicesPerZone(int v) { this.devicesPerZone = v; return this; }
    public Builder argon2(int iterations, int memoryKib, int parallelism) {
      this.argon2Iterations = iterations; this.argon2MemoryKib = memoryKib;
      this.argon2Parallelism = parallelism; return this;
    }

    public ServerConfig build() {
      return new ServerConfig(tlsPort, wsPort, dbFile, keystore, keystorePassword, buildingId,
          heartbeatIntervalMs, heartbeatMaxMissed, retryBaseDelayMs, maxRetries,
          writerQueueCapacity, statusBatchMs, seedDemo, devicesPerZone,
          argon2Iterations, argon2MemoryKib, argon2Parallelism);
    }
  }
}
