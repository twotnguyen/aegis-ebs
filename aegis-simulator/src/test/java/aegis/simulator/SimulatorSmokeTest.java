package aegis.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests: real TLS loopback fleet against a minimal EBSP/2.0 fixture server.
 * Covers the 100-200 agent band, fault injection accounting, and seed reproducibility.
 */
@Timeout(180)
class SimulatorSmokeTest {

  @TempDir Path tmp;

  private SimulatorConfig config(int port, Path truststore, int agents, String scenario,
                                 long seed, int duration, long stagger) {
    return new SimulatorConfig("127.0.0.1", port, agents, seed, scenario, FaultProfile.preset(scenario),
        truststore, "changeit", "SIM-DEV", "simulation-secret", duration, stagger);
  }

  @Test void mixedScenarioFleetConnectsAcksAndInjectsAllFaultKinds() throws Exception {
    SimTestUtil.TlsPair tls = SimTestUtil.generateTls(tmp);
    SSLContext serverCtx = TlsSupport.serverContext(tls.keystore(), tls.password());
    try (LoopbackEbspServer server = new LoopbackEbspServer(serverCtx, 2)) {
      SimulatorConfig cfg = config(server.port(), tls.truststore(), 30, "mixed", 42, 6, 2);
      SimStats.Snapshot s = SimulatorMain.run(cfg);

      assertEquals(30, s.agentsStarted());
      assertEquals(30, s.agentsFinished(), "every agent must run to the deadline");
      assertTrue(s.connectSuccess() >= 30, "all agents connect at least once: " + s.connectSuccess());
      assertTrue(s.alertsReceived() >= 60, "2 pushes per connection minimum: " + s.alertsReceived());
      assertTrue(s.acksSent() > 0);
      assertTrue(s.acksDropped() > 0, "mixed scenario must drop some ACKs (seed 42)");
      assertTrue(s.acksDelayed() > 0, "mixed scenario must delay some ACKs");
      assertTrue(s.duplicateAcks() > 0, "mixed scenario must duplicate some ACKs");
      assertTrue(s.disconnects() > 0, "mixed scenario must inject disconnects");
      assertTrue(s.reconnects() > 0, "disconnects must be followed by reconnects");
      assertTrue(s.syncRequests() > 0, "reconnect must trigger SYNC_REQUEST");
      assertTrue(s.heartbeats() > 0, "agents answer server HEARTBEAT");

      assertTrue(server.sawTlsSocket, "fleet must use TLS");
      assertTrue(server.connections.get() >= 30);
      assertTrue(server.acksReceived.get() > 0);
      assertTrue(server.syncRequests.get() > 0);
      assertTrue(server.heartbeatAcks.get() > 0);
      assertEquals(30, server.deviceIds.stream().distinct().count(), "unique device identity per agent");
      // every ACK copy carries a fresh messageId (wire contract)
      assertEquals(server.ackLog.size(), server.ackLog.stream().distinct().count());
    }
  }

  @Test void cleanScenarioAcksEveryAlertExactlyOnce() throws Exception {
    SimTestUtil.TlsPair tls = SimTestUtil.generateTls(tmp);
    SSLContext serverCtx = TlsSupport.serverContext(tls.keystore(), tls.password());
    try (LoopbackEbspServer server = new LoopbackEbspServer(serverCtx, 2)) {
      SimulatorConfig cfg = config(server.port(), tls.truststore(), 12, "clean", 7, 4, 2);
      SimStats.Snapshot s = SimulatorMain.run(cfg);

      assertEquals(12, s.agentsFinished());
      assertEquals(12, s.connectSuccess());
      assertEquals(24, s.alertsReceived());
      assertEquals(24, s.acksSent(), "clean fleet: exactly one ACK per alert");
      assertEquals(0, s.acksDropped());
      assertEquals(0, s.acksDelayed());
      assertEquals(0, s.duplicateAcks());
      assertEquals(0, s.disconnects());
      assertEquals(0, s.connectionErrors());
      assertEquals(12, s.heartbeats());
      assertEquals(24, server.acksReceived.get(), "server observed every ACK");
      assertEquals(12, server.heartbeatAcks.get());
    }
  }

  @Test void sameSeedReproducesIdenticalFaultAccounting() throws Exception {
    SimTestUtil.TlsPair tls = SimTestUtil.generateTls(tmp);
    SSLContext serverCtx = TlsSupport.serverContext(tls.keystore(), tls.password());
    SimStats.Snapshot first;
    SimStats.Snapshot second;
    try (LoopbackEbspServer server = new LoopbackEbspServer(serverCtx, 2)) {
      first = SimulatorMain.run(config(server.port(), tls.truststore(), 8, "lossy", 99, 3, 1));
    }
    try (LoopbackEbspServer server = new LoopbackEbspServer(serverCtx, 2)) {
      second = SimulatorMain.run(config(server.port(), tls.truststore(), 8, "lossy", 99, 3, 1));
    }
    assertEquals(16, first.alertsReceived());
    assertEquals(first.alertsReceived(), second.alertsReceived());
    assertEquals(first.acksSent(), second.acksSent(), "same seed => same ACK count");
    assertEquals(first.acksDropped(), second.acksDropped(), "same seed => same dropped ACKs");
    assertEquals(first.duplicateAcks(), second.duplicateAcks());
    assertEquals(first.disconnects(), second.disconnects());
    assertTrue(first.acksDropped() > 0, "lossy scenario must actually drop ACKs (seed 99)");
    assertEquals(first.acksSent() + first.acksDropped(), 16, "every alert is acked or dropped");
  }

  @Test void hundredAgentFleetSmoke() throws Exception {
    SimTestUtil.TlsPair tls = SimTestUtil.generateTls(tmp);
    SSLContext serverCtx = TlsSupport.serverContext(tls.keystore(), tls.password());
    try (LoopbackEbspServer server = new LoopbackEbspServer(serverCtx, 2)) {
      SimulatorConfig cfg = config(server.port(), tls.truststore(), 100, "clean", 42, 5, 1);
      SimStats.Snapshot s = SimulatorMain.run(cfg);

      assertEquals(100, s.agentsStarted());
      assertEquals(100, s.agentsFinished(), "100-agent fleet completes");
      assertEquals(100, s.connectSuccess(), "100 TLS handshakes on loopback");
      assertEquals(200, s.alertsReceived());
      assertEquals(200, s.acksSent());
      assertEquals(0, s.connectionErrors());
      assertEquals(100, server.connections.get());
      assertEquals(200, server.acksReceived.get());
      assertEquals(100, server.deviceIds.stream().distinct().count());
    }
  }
}
