package aegis.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Normative severity rules from aegis-dashboard/docs/status-contract.md. */
class ZoneStatusTest {

  @Test
  void offlineWhenNoDeviceOnline() {
    assertEquals("offline", ZoneStatus.deriveSeverity(12, 0, 100, 100, 0));
    assertEquals("offline", ZoneStatus.deriveSeverity(12, 0, 0, 0, 5));
  }

  @Test
  void criticalWhenFailedRatioAtLeast20Percent() {
    // failed/received = 2/10 = 0.2 -> critical (even though acked ratio is high)
    assertEquals("critical", ZoneStatus.deriveSeverity(12, 12, 10, 8, 2));
  }

  @Test
  void criticalWhenAckedRatioBelow60Percent() {
    // failed/received = 1/10 < 0.2 but acked/received = 5/10 < 0.6 -> critical
    assertEquals("critical", ZoneStatus.deriveSeverity(12, 12, 10, 5, 1));
  }

  @Test
  void warningWhenFailedButRatiosHealthy() {
    // 1/100 < 0.2 and 99/100 >= 0.6 -> not critical, but failed > 0 -> warning
    assertEquals("warning", ZoneStatus.deriveSeverity(12, 12, 100, 99, 1));
  }

  @Test
  void warningWhenSomeDevicesOffline() {
    assertEquals("warning", ZoneStatus.deriveSeverity(12, 10, 0, 0, 0));
  }

  @Test
  void warningWhenUnackedRemain() {
    assertEquals("warning", ZoneStatus.deriveSeverity(12, 12, 10, 9, 0));
  }

  @Test
  void onlineWhenAllHealthy() {
    assertEquals("online", ZoneStatus.deriveSeverity(12, 12, 10, 10, 0));
    assertEquals("online", ZoneStatus.deriveSeverity(12, 12, 0, 0, 0));
  }

  @Test
  void recordDelegatesToRules() {
    assertEquals("critical", new ZoneStatus(12, 12, 10, 5, 1).severity());
    assertEquals("online", new ZoneStatus(12, 12, 10, 10, 0).severity());
  }
}
