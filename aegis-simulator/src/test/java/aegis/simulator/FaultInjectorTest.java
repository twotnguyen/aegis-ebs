package aegis.simulator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Seed reproducibility and rate semantics of the fault injector (pure logic). */
class FaultInjectorTest {

  private static List<FaultDecision> decisions(FaultProfile p, long seed, int n) {
    FaultInjector inj = new FaultInjector(p, new Random(seed));
    List<FaultDecision> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) out.add(inj.decide());
    return out;
  }

  @Test void sameSeedProducesIdenticalDecisionStream() {
    FaultProfile mixed = FaultProfile.preset("mixed");
    assertEquals(decisions(mixed, 42, 1000), decisions(mixed, 42, 1000));
    assertEquals(decisions(mixed, 99, 500), decisions(mixed, 99, 500));
  }

  @Test void differentSeedsDiverge() {
    FaultProfile mixed = FaultProfile.preset("mixed");
    assertNotEquals(decisions(mixed, 1, 200), decisions(mixed, 2, 200));
  }

  @Test void cleanProfileNeverInjectsFaults() {
    for (FaultDecision d : decisions(FaultProfile.preset("clean"), 7, 1000)) {
      assertFalse(d.dropAck());
      assertEquals(0, d.delayMs());
      assertEquals(1, d.ackCopies());
      assertFalse(d.disconnectAfter());
    }
  }

  @Test void lossyDropRateApproximatesConfiguredProbability() {
    long drops = decisions(FaultProfile.preset("lossy"), 42, 10_000).stream()
        .filter(FaultDecision::dropAck).count();
    assertTrue(drops > 2700 && drops < 3300, "expected ~30% drops, got " + drops);
  }

  @Test void delayedProfileStaysInRange() {
    for (FaultDecision d : decisions(FaultProfile.preset("delayed"), 42, 1000)) {
      assertTrue(d.delayMs() >= 500 && d.delayMs() <= 2000, "delay out of range: " + d.delayMs());
      assertFalse(d.dropAck());
    }
  }

  @Test void duplicateProfileEmitsOneOrTwoCopies() {
    List<FaultDecision> ds = decisions(FaultProfile.preset("duplicate"), 42, 1000);
    assertTrue(ds.stream().allMatch(d -> d.ackCopies() == 1 || d.ackCopies() == 2));
    assertTrue(ds.stream().anyMatch(d -> d.ackCopies() == 2));
    assertTrue(ds.stream().anyMatch(d -> d.ackCopies() == 1));
    long dupes = ds.stream().filter(d -> d.ackCopies() == 2).count();
    assertTrue(dupes > 400 && dupes < 600, "expected ~50% duplication, got " + dupes);
  }

  @Test void flakyProfileDisconnectsAtConfiguredRate() {
    long disc = decisions(FaultProfile.preset("flaky"), 42, 10_000).stream()
        .filter(FaultDecision::disconnectAfter).count();
    assertTrue(disc > 2700 && disc < 3300, "expected ~30% disconnects, got " + disc);
  }

  @Test void customOverridesAreHonored() {
    FaultProfile p = FaultProfile.preset("clean").withDropRate(1.0).withDuplicate(1.0, 3).withDisconnectRate(1.0);
    for (FaultDecision d : decisions(p, 5, 100)) {
      assertTrue(d.dropAck());
      assertEquals(3, d.ackCopies());
      assertTrue(d.disconnectAfter());
    }
  }

  @Test void invalidProfilesRejected() {
    assertThrows(IllegalArgumentException.class, () -> new FaultProfile(1.5, 0, 0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FaultProfile(0, -1, 0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FaultProfile(0, 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> FaultProfile.preset("bogus"));
  }
}
