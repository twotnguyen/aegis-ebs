package aegis.simulator;

import java.util.random.RandomGenerator;

/**
 * Seed-reproducible fault injection. Each {@link #decide()} consumes exactly four
 * uniform doubles from the injected RNG in a fixed order (drop, delay-jitter,
 * duplicate, disconnect), so the decision stream depends only on (profile, seed)
 * — never on timing or thread scheduling. Give each agent its own RNG derived
 * deterministically from the master seed (see SimulatorMain).
 */
public final class FaultInjector {

  private final FaultProfile profile;
  private final RandomGenerator rng;

  public FaultInjector(FaultProfile profile, RandomGenerator rng) {
    this.profile = profile;
    this.rng = rng;
  }

  public FaultProfile profile() { return profile; }

  public FaultDecision decide() {
    double rDrop = rng.nextDouble();
    double rDelay = rng.nextDouble();
    double rDup = rng.nextDouble();
    double rDisc = rng.nextDouble();

    boolean drop = rDrop < profile.dropRate();
    long delay = profile.delayBaseMs() + (long) (rDelay * (profile.delayJitterMs() + 1));
    int copies = rDup < profile.duplicateRate() ? Math.max(2, profile.duplicateCount()) : 1;
    boolean disconnect = rDisc < profile.disconnectRate();
    return new FaultDecision(drop, delay, copies, disconnect);
  }
}
