package aegis.simulator;

/**
 * Fault-injection rates for simulated devices (SIMULATION/TRAINING).
 * All rates in [0,1]; delays in ms. Presets map to CLI --scenario values.
 */
public record FaultProfile(
    double dropRate, long delayBaseMs, long delayJitterMs,
    double duplicateRate, int duplicateCount, double disconnectRate) {

  public FaultProfile {
    if (dropRate < 0 || dropRate > 1) throw new IllegalArgumentException("dropRate must be in [0,1]: " + dropRate);
    if (duplicateRate < 0 || duplicateRate > 1) throw new IllegalArgumentException("duplicateRate must be in [0,1]: " + duplicateRate);
    if (disconnectRate < 0 || disconnectRate > 1) throw new IllegalArgumentException("disconnectRate must be in [0,1]: " + disconnectRate);
    if (delayBaseMs < 0 || delayJitterMs < 0) throw new IllegalArgumentException("delays must be >= 0");
    if (duplicateCount < 1) throw new IllegalArgumentException("duplicateCount must be >= 1: " + duplicateCount);
  }

  public static FaultProfile preset(String scenario) {
    return switch (scenario) {
      case "clean" -> new FaultProfile(0, 0, 0, 0, 1, 0);
      case "lossy" -> new FaultProfile(0.3, 0, 0, 0, 1, 0);
      case "delayed" -> new FaultProfile(0, 500, 1500, 0, 1, 0);
      case "duplicate" -> new FaultProfile(0, 0, 0, 0.5, 2, 0);
      case "flaky" -> new FaultProfile(0, 0, 0, 0, 1, 0.3);
      case "mixed" -> new FaultProfile(0.15, 300, 1200, 0.3, 2, 0.2);
      default -> throw new IllegalArgumentException(
          "unknown scenario '" + scenario + "' (clean|lossy|delayed|duplicate|flaky|mixed)");
    };
  }

  public FaultProfile withDropRate(double v) { return new FaultProfile(v, delayBaseMs, delayJitterMs, duplicateRate, duplicateCount, disconnectRate); }
  public FaultProfile withDelay(long base, long jitter) { return new FaultProfile(dropRate, base, jitter, duplicateRate, duplicateCount, disconnectRate); }
  public FaultProfile withDuplicate(double rate, int count) { return new FaultProfile(dropRate, delayBaseMs, delayJitterMs, rate, count, disconnectRate); }
  public FaultProfile withDisconnectRate(double v) { return new FaultProfile(dropRate, delayBaseMs, delayJitterMs, duplicateRate, duplicateCount, v); }
}
