package aegis.simulator;

import javax.net.ssl.SSLContext;
import java.time.Clock;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CLI orchestrator: spawns a headless fleet of {@link SimAgent}s on virtual threads
 * (one per simulated device, recommended 100-200), each with a deterministic RNG
 * derived from the master --seed, runs for --duration seconds, prints a summary.
 *
 * <p>Run (no pom changes needed):
 * <pre>
 * mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:java \
 *   -Dexec.mainClass=aegis.simulator.SimulatorMain \
 *   -Dexec.args="--agents 150 --scenario mixed --seed 42 --host localhost --port 9443 \
 *                --truststore truststore.p12 --truststore-password changeit --duration 30"
 * </pre>
 * Exit codes: 0 = fleet ran and every agent finished; 1 = runtime failure; 2 = bad CLI.
 */
public final class SimulatorMain {

  private SimulatorMain() {}

  public static void main(String[] args) {
    if (SimulatorConfig.helpRequested(args)) {
      System.out.println(SimulatorConfig.usage());
      return;
    }
    SimulatorConfig cfg;
    try {
      cfg = SimulatorConfig.parse(args);
    } catch (IllegalArgumentException ex) {
      System.err.println("config error: " + ex.getMessage());
      System.err.println(SimulatorConfig.usage());
      System.exit(2);
      return;
    }
    System.out.println("AEGIS SIMULATOR — SIMULATION/TRAINING ONLY — NOT REAL DEVICES");
    System.out.printf("fleet=%d seed=%d scenario=%s target=%s:%d duration=%ds faults=%s%n",
        cfg.agents(), cfg.seed(), cfg.scenario(), cfg.host(), cfg.port(), cfg.durationSeconds(), cfg.faults());
    try {
      SimStats.Snapshot snap = run(cfg);
      System.out.println(snap.summary());
      boolean ok = snap.connectSuccess() > 0 && snap.agentsFinished() == cfg.agents();
      System.exit(ok ? 0 : 1);
    } catch (Exception ex) {
      System.err.println("simulator failed: " + ex);
      ex.printStackTrace();
      System.exit(1);
    }
  }

  /** Run the fleet to completion (testable entry point — no System.exit). */
  public static SimStats.Snapshot run(SimulatorConfig cfg) throws Exception {
    SSLContext tls = TlsSupport.clientContext(cfg.truststore(), cfg.truststorePassword());
    SimStats stats = new SimStats();
    Random master = new Random(cfg.seed());
    long deadline = System.currentTimeMillis() + cfg.durationSeconds() * 1000L;

    ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (int i = 0; i < cfg.agents(); i++) {
        long agentSeed = master.nextLong(); // deterministic per-index derivation
        String deviceId = cfg.devicePrefix() + "-" + String.format("%04d", i + 1);
        FaultInjector injector = new FaultInjector(cfg.faults(), new Random(agentSeed));
        stats.agentsStarted.increment();
        exec.submit(new SimAgent(deviceId, cfg, tls, injector, stats, deadline, Clock.systemUTC()));
        if (cfg.staggerMs() > 0 && i < cfg.agents() - 1) Thread.sleep(cfg.staggerMs());
      }
      exec.shutdown();
      long graceMs = cfg.durationSeconds() * 1000L + 30_000L;
      if (!exec.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
        exec.shutdownNow();
        exec.awaitTermination(10, TimeUnit.SECONDS);
      }
    } finally {
      exec.shutdownNow();
    }
    return stats.snapshot();
  }
}
