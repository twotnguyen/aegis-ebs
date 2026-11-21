package aegis.server;

import aegis.core.DeviceLocation;
import aegis.core.Grants;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRegistryTest {

  /** Mutable clock so heartbeat accounting can be advanced deterministically. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-21T00:00:00Z");
    void advance(long ms) { now = now.plusMillis(ms); }
    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  private ClientSession session(SessionRegistry registry, MutableClock clock,
                                TestSupport.SocketPair pair, String deviceId) throws Exception {
    ClientSession s = new ClientSession(Json.uuid(), pair.sessionSide(), new FrameCodecImpl(),
        256, clock, Executors.newVirtualThreadPerTaskExecutor(),
        registry::unregister, x -> x.close());
    registry.register(s);
    s.bindDevice(deviceId, new DeviceLocation(deviceId, "AEGIS-BLD-A", "floor-1", "F01-A"), 1, "F01-A");
    registry.deviceOnline(s);
    return s;
  }

  @Test
  void suspectOnlyAfterThreeMissedIntervals() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    MutableClock clock = new MutableClock();
    try (TestSupport.SocketPair pair = TestSupport.socketPair();
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      ClientSession s = session(registry, clock, pair, "dev-1");
      // 29s elapsed at 10s intervals = 2 missed -> not suspect yet
      clock.advance(29_000);
      assertTrue(registry.sweep(clock, 10_000, 3).isEmpty());
      // 30s = 3 missed -> suspect
      clock.advance(1_000);
      List<ClientSession> suspects = registry.sweep(clock, 10_000, 3);
      assertEquals(1, suspects.size());
      assertTrue(s.isSuspect());
      s.close();
    }
  }

  @Test
  void heartbeatTouchResetsMissCount() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    MutableClock clock = new MutableClock();
    try (TestSupport.SocketPair pair = TestSupport.socketPair()) {
      ClientSession s = session(registry, clock, pair, "dev-2");
      clock.advance(25_000);
      s.touchHeartbeat(); // HEARTBEAT received
      clock.advance(25_000);
      assertTrue(registry.sweep(clock, 10_000, 3).isEmpty(), "touch must reset the window");
      clock.advance(5_000);
      assertEquals(1, registry.sweep(clock, 10_000, 3).size());
      s.close();
    }
  }

  @Test
  void unauthenticatedSessionsAreNotSwept() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    MutableClock clock = new MutableClock();
    try (TestSupport.SocketPair pair = TestSupport.socketPair()) {
      ClientSession s = new ClientSession(Json.uuid(), pair.sessionSide(), new FrameCodecImpl(),
          256, clock, Executors.newVirtualThreadPerTaskExecutor(), registry::unregister, x -> x.close());
      registry.register(s);
      clock.advance(60_000);
      assertTrue(registry.sweep(clock, 10_000, 3).isEmpty());
      s.close();
    }
  }

  @Test
  void reconnectReplacesStaleDeviceSession() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    MutableClock clock = new MutableClock();
    try (TestSupport.SocketPair oldPair = TestSupport.socketPair();
         TestSupport.SocketPair newPair = TestSupport.socketPair()) {
      ClientSession old = session(registry, clock, oldPair, "dev-3");
      ClientSession fresh = session(registry, clock, newPair, "dev-3");
      assertTrue(old.isClosed(), "stale connection must be closed on reconnect");
      assertEquals(fresh, registry.deviceSession("dev-3"));
      assertEquals(1, registry.onlineDeviceCount());
      fresh.close();
      assertEquals(0, registry.onlineDeviceCount());
    }
  }

  @Test
  void operatorBindingCarriesRoleAndGrants() throws Exception {
    SessionRegistry registry = new SessionRegistry();
    MutableClock clock = new MutableClock();
    try (TestSupport.SocketPair pair = TestSupport.socketPair()) {
      ClientSession s = new ClientSession(Json.uuid(), pair.sessionSide(), new FrameCodecImpl(),
          256, clock, Executors.newVirtualThreadPerTaskExecutor(), registry::unregister, x -> x.close());
      registry.register(s);
      assertFalse(s.isAuthenticated());
      Grants grants = Grants.of(List.of("AEGIS-BLD-A"), java.util.Map.of());
      s.bindOperator("user-1", Role.SECURITY_OPERATOR, grants);
      assertTrue(s.isAuthenticated());
      assertEquals(Role.SECURITY_OPERATOR, s.role());
      assertTrue(s.grants().coversBuilding("AEGIS-BLD-A"));
      s.close();
    }
  }
}
