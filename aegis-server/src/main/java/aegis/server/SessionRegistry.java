package aegis.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live session registry + heartbeat accounting. A session is suspect after
 * {@code maxMissed} consecutive missed heartbeat intervals (10s / 3 misses by
 * default, docs/PROTOCOL.md). The sweep is pure logic over an injected Clock so
 * tests can advance time deterministically; callers audit + close suspects.
 */
public final class SessionRegistry {

  private final Map<String, ClientSession> byId = new ConcurrentHashMap<>();
  private final Map<String, ClientSession> byDeviceId = new ConcurrentHashMap<>();

  public void register(ClientSession session) {
    byId.put(session.sessionId(), session);
  }

  public void unregister(ClientSession session) {
    byId.remove(session.sessionId());
    if (session.deviceId() != null) {
      byDeviceId.remove(session.deviceId(), session);
    }
  }

  /** Indexes an authenticated device session (replaces a stale connection). */
  public ClientSession deviceOnline(ClientSession session) {
    ClientSession previous = byDeviceId.put(session.deviceId(), session);
    if (previous != null && previous != session) {
      previous.close();
    }
    return previous;
  }

  public ClientSession deviceSession(String deviceId) {
    ClientSession s = byDeviceId.get(deviceId);
    return s != null && !s.isClosed() ? s : null;
  }

  public ClientSession byId(String sessionId) { return byId.get(sessionId); }

  public List<ClientSession> all() { return List.copyOf(byId.values()); }

  public List<ClientSession> deviceSessions() {
    List<ClientSession> out = new ArrayList<>();
    for (ClientSession s : byDeviceId.values()) {
      if (!s.isClosed()) out.add(s);
    }
    return out;
  }

  public int onlineDeviceCount() { return deviceSessions().size(); }

  /** Total registered sessions incl. pre-auth ones (connection cap, S-07). */
  public int size() { return byId.size(); }

  /** Sessions that missed {@code maxMissed} heartbeat intervals as of {@code now}. */
  public List<ClientSession> sweep(Instant now, long intervalMs, int maxMissed) {
    List<ClientSession> suspects = new ArrayList<>();
    for (ClientSession s : byId.values()) {
      if (s.isClosed() || !s.isAuthenticated()) continue;
      long missed = Duration.between(s.lastHeartbeatAt(), now).toMillis() / intervalMs;
      if (missed >= maxMissed) {
        s.markSuspect();
        suspects.add(s);
      }
    }
    return suspects;
  }

  public List<ClientSession> sweep(Clock clock, long intervalMs, int maxMissed) {
    return sweep(clock.instant(), intervalMs, maxMissed);
  }

  public void closeAll() {
    for (ClientSession s : List.copyOf(byId.values())) {
      s.close();
    }
    byId.clear();
    byDeviceId.clear();
  }
}
