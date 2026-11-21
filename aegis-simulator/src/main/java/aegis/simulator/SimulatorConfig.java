package aegis.simulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulator CLI configuration (headless, no JavaFX). Recommended fleet size is
 * 100-200 agents; 1..1000 accepted for tests/small demos.
 *
 * <p>Flags: --host --port --agents --seed --scenario (clean|lossy|delayed|duplicate|flaky|mixed)
 * --truststore --truststore-password --device-prefix --device-secret --duration (seconds)
 * --stagger-ms, plus per-rate overrides --drop-rate --delay-ms --delay-jitter-ms
 * --dup-rate --dup-count --disconnect-rate. --help prints usage.
 */
public record SimulatorConfig(
    String host, int port, int agents, long seed, String scenario, FaultProfile faults,
    Path truststore, String truststorePassword, String devicePrefix, String deviceSecret,
    int durationSeconds, long staggerMs) {

  public static final int MAX_AGENTS = 1000;

  public SimulatorConfig {
    if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    if (agents < 1 || agents > MAX_AGENTS) throw new IllegalArgumentException("agents must be 1.." + MAX_AGENTS + ": " + agents);
    if (durationSeconds < 1) throw new IllegalArgumentException("duration must be >= 1s: " + durationSeconds);
    if (staggerMs < 0) throw new IllegalArgumentException("stagger-ms must be >= 0");
    if (devicePrefix == null || devicePrefix.isBlank()) throw new IllegalArgumentException("device-prefix required");
  }

  public static SimulatorConfig defaults() {
    return new SimulatorConfig("localhost", 9443, 100, 42L, "mixed", FaultProfile.preset("mixed"),
        Path.of("truststore.p12"), "changeit", "SIM-DEV", "simulation-secret", 20, 5L);
  }

  public static boolean helpRequested(String[] args) {
    for (String a : args) if ("--help".equals(a) || "-h".equals(a)) return true;
    return false;
  }

  public static String usage() {
    return """
        AEGIS simulator — headless TLS device fleet (SIMULATION/TRAINING ONLY)
          --host <h>                 server host (default localhost)
          --port <p>                 server TLS port (default 9443)
          --agents <n>               fleet size 1..1000, recommended 100-200 (default 100)
          --seed <s>                 master RNG seed — reproducible fault injection (default 42)
          --scenario <name>          clean | lossy | delayed | duplicate | flaky | mixed (default mixed)
          --drop-rate <0..1>         override ACK drop probability
          --delay-ms <ms>            override ACK base delay
          --delay-jitter-ms <ms>     override ACK delay jitter
          --dup-rate <0..1>          override duplicate-ACK probability
          --dup-count <n>            total ACK copies when duplicating (>= 2 effective)
          --disconnect-rate <0..1>   override post-delivery disconnect probability
          --truststore <path>        client truststore (PKCS12/JKS) verifying the server
          --truststore-password <pw> truststore password (default changeit)
          --device-prefix <p>        device ids <p>-0001.. (default SIM-DEV)
          --device-secret <s>        AUTH_DEVICE secret (default simulation-secret)
          --duration <seconds>       fleet run time (default 20)
          --stagger-ms <ms>          delay between agent starts (default 5)
          --help                     this message
        """;
  }

  public static SimulatorConfig parse(String[] args) {
    SimulatorConfig d = defaults();
    String host = d.host(); int port = d.port(); int agents = d.agents(); long seed = d.seed();
    String scenario = d.scenario(); String prefix = d.devicePrefix(); String secret = d.deviceSecret();
    Path truststore = d.truststore(); String tsPassword = d.truststorePassword();
    int duration = d.durationSeconds(); long stagger = d.staggerMs();
    Double dropRate = null, dupRate = null, discRate = null;
    Long delayMs = null, delayJitter = null; Integer dupCount = null;
    List<String> unknown = new ArrayList<>();

    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
        case "--host", "--port", "--agents", "--seed", "--scenario", "--truststore",
             "--truststore-password", "--device-prefix", "--device-secret", "--duration",
             "--stagger-ms", "--drop-rate", "--delay-ms", "--delay-jitter-ms",
             "--dup-rate", "--dup-count", "--disconnect-rate" -> {
          if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + a);
          String v = args[++i];
          try {
            switch (a) {
              case "--host" -> host = v;
              case "--port" -> port = Integer.parseInt(v);
              case "--agents" -> agents = Integer.parseInt(v);
              case "--seed" -> seed = Long.parseLong(v);
              case "--scenario" -> scenario = v;
              case "--truststore" -> truststore = Path.of(v);
              case "--truststore-password" -> tsPassword = v;
              case "--device-prefix" -> prefix = v;
              case "--device-secret" -> secret = v;
              case "--duration" -> duration = Integer.parseInt(v);
              case "--stagger-ms" -> stagger = Long.parseLong(v);
              case "--drop-rate" -> dropRate = Double.parseDouble(v);
              case "--delay-ms" -> delayMs = Long.parseLong(v);
              case "--delay-jitter-ms" -> delayJitter = Long.parseLong(v);
              case "--dup-rate" -> dupRate = Double.parseDouble(v);
              case "--dup-count" -> dupCount = Integer.parseInt(v);
              case "--disconnect-rate" -> discRate = Double.parseDouble(v);
              default -> throw new IllegalStateException(a);
            }
          } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid number for " + a + ": " + v);
          }
        }
        default -> unknown.add(a);
      }
    }
    if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown flags: " + unknown);

    FaultProfile profile = FaultProfile.preset(scenario);
    if (dropRate != null) profile = profile.withDropRate(dropRate);
    if (delayMs != null || delayJitter != null) {
      profile = profile.withDelay(delayMs != null ? delayMs : profile.delayBaseMs(),
          delayJitter != null ? delayJitter : profile.delayJitterMs());
    }
    if (dupRate != null || dupCount != null) {
      profile = profile.withDuplicate(dupRate != null ? dupRate : profile.duplicateRate(),
          dupCount != null ? dupCount : profile.duplicateCount());
    }
    if (discRate != null) profile = profile.withDisconnectRate(discRate);

    return new SimulatorConfig(host, port, agents, seed, scenario, profile, truststore, tsPassword,
        prefix, secret, duration, stagger);
  }
}
