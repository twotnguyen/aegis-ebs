package aegis.simulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorConfigTest {

  @Test void defaultsMatchTaskBrief() {
    SimulatorConfig d = SimulatorConfig.defaults();
    assertEquals("localhost", d.host());
    assertEquals(9443, d.port());
    assertEquals(100, d.agents(), "default fleet in the 100-200 band");
    assertEquals(42, d.seed());
    assertEquals("mixed", d.scenario());
    assertEquals(20, d.durationSeconds());
  }

  @Test void parsesFleetScenarioAndSeed() {
    SimulatorConfig c = SimulatorConfig.parse(new String[]{
        "--agents", "200", "--scenario", "flaky", "--seed", "7",
        "--host", "127.0.0.1", "--port", "19443", "--duration", "30",
        "--truststore", "/tmp/ts.p12", "--truststore-password", "pw",
        "--device-prefix", "LAB-DEV", "--stagger-ms", "2"});
    assertEquals(200, c.agents());
    assertEquals("flaky", c.scenario());
    assertEquals(0.3, c.faults().disconnectRate());
    assertEquals(7, c.seed());
    assertEquals(19443, c.port());
    assertEquals(30, c.durationSeconds());
    assertEquals(Path.of("/tmp/ts.p12"), c.truststore());
    assertEquals("pw", c.truststorePassword());
    assertEquals("LAB-DEV", c.devicePrefix());
    assertEquals(2, c.staggerMs());
  }

  @Test void rateOverridesApplyOnTopOfScenario() {
    SimulatorConfig c = SimulatorConfig.parse(new String[]{
        "--scenario", "lossy", "--drop-rate", "0.9", "--delay-ms", "100",
        "--delay-jitter-ms", "50", "--dup-rate", "0.4", "--dup-count", "3",
        "--disconnect-rate", "0.1"});
    assertEquals(0.9, c.faults().dropRate());
    assertEquals(100, c.faults().delayBaseMs());
    assertEquals(50, c.faults().delayJitterMs());
    assertEquals(0.4, c.faults().duplicateRate());
    assertEquals(3, c.faults().duplicateCount());
    assertEquals(0.1, c.faults().disconnectRate());
  }

  @Test void scenarioPresets() {
    assertEquals(0.3, SimulatorConfig.parse(new String[]{"--scenario", "lossy"}).faults().dropRate());
    assertEquals(500, SimulatorConfig.parse(new String[]{"--scenario", "delayed"}).faults().delayBaseMs());
    assertEquals(0.5, SimulatorConfig.parse(new String[]{"--scenario", "duplicate"}).faults().duplicateRate());
    assertEquals(0, SimulatorConfig.parse(new String[]{"--scenario", "clean"}).faults().dropRate());
  }

  @Test void invalidValuesRejected() {
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--agents", "0"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--agents", "1001"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--duration", "0"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--scenario", "bogus"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--drop-rate", "1.5"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--port", "x"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--bogus"}));
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.parse(new String[]{"--agents"}));
  }

  @Test void helpDetection() {
    assertTrue(SimulatorConfig.helpRequested(new String[]{"--help"}));
    assertTrue(SimulatorConfig.helpRequested(new String[]{"--agents", "100", "-h"}));
    assertFalse(SimulatorConfig.helpRequested(new String[]{"--agents", "100"}));
    assertTrue(SimulatorConfig.usage().contains("--scenario"));
  }
}
