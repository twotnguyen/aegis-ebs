package aegis.server;

import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AlertRevision;
import aegis.persistence.DeliveryState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges EBSP state into the dashboard status/1 contract
 * (aegis-dashboard/docs/status-contract.md): full STATUS_SNAPSHOT on connect,
 * batched STATUS_DELTA with only changed zones/metrics/alerts afterwards.
 * Zone counters are monotonic; tombstoned alerts never appear in activeAlerts.
 */
public final class StatusFeedService implements StatusNotifier {
  private static final Logger LOG = LoggerFactory.getLogger(StatusFeedService.class);

  private static final class Counters {
    final AtomicLong received = new AtomicLong();
    final AtomicLong acknowledged = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
  }

  private final String buildingId;
  private final ServerStore store;
  private final AlertRepo alerts;
  private final SessionRegistry sessions;
  private final MetricsRegistry metrics;
  private final Clock clock;

  private final Map<String, ServerStore.DeviceInfo> deviceInfo = new HashMap<>();
  private final Map<Integer, List<String>> zonesByFloor = new java.util.LinkedHashMap<>();
  private final Map<String, Integer> devicesTotalByZone = new HashMap<>();
  private final Map<String, Counters> counters = new ConcurrentHashMap<>();
  private final List<WsConnection> subscribers = new CopyOnWriteArrayList<>();
  private volatile ArrayNode alertCache;
  private final AtomicLong alertsGeneration = new AtomicLong();
  private final AtomicLong cacheGeneration = new AtomicLong(-1);
  private volatile ObjectNode lastBroadcast;

  public StatusFeedService(String buildingId, ServerStore store, AlertRepo alerts,
                           SessionRegistry sessions, MetricsRegistry metrics, Clock clock) {
    this.buildingId = Objects.requireNonNull(buildingId);
    this.store = Objects.requireNonNull(store);
    this.alerts = Objects.requireNonNull(alerts);
    this.sessions = Objects.requireNonNull(sessions);
    this.metrics = Objects.requireNonNull(metrics);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Loads topology + device placement once (demo world is static). */
  public void init() throws SQLException {
    deviceInfo.putAll(store.deviceInfoMap());
    zonesByFloor.putAll(store.zonesByFloor(buildingId));
    devicesTotalByZone.putAll(store.deviceCountByZone(buildingId));
    rebuildAlertCache();
  }

  private Counters counters(String zoneCode) {
    return counters.computeIfAbsent(zoneCode, k -> new Counters());
  }

  private String zoneOf(String deviceId) {
    ServerStore.DeviceInfo info = deviceInfo.get(deviceId);
    return info == null ? null : info.zoneCode();
  }

  // ---- StatusNotifier hooks ------------------------------------------------

  @Override
  public void pushWritten(String deviceId) {
    String zone = zoneOf(deviceId);
    if (zone != null) counters(zone).received.incrementAndGet();
  }

  @Override
  public void ackReceived(String deviceId) {
    String zone = zoneOf(deviceId);
    if (zone != null) counters(zone).acknowledged.incrementAndGet();
  }

  @Override
  public void deliveryFailed(String deviceId) {
    String zone = zoneOf(deviceId);
    if (zone != null) counters(zone).failed.incrementAndGet();
  }

  @Override
  public void alertsChanged() {
    // Coalesced via a monotonic generation: a publish during a rebuild bumps the
    // generation again so the next cycle/subscribe refreshes. No lost dirty bit.
    alertsGeneration.incrementAndGet();
  }

  @Override
  public void connectivityChanged() {
    // online counts are gauges computed at broadcast time; nothing to cache
  }

  // ---- snapshot / delta -----------------------------------------------------

  public ObjectNode snapshot() {
    ObjectNode root = Json.obj();
    root.put("version", 1);
    root.put("type", "STATUS_SNAPSHOT");
    root.put("buildingId", buildingId);
    root.put("ts", Json.ts(clock.instant()));
    root.set("floors", floorsNode(true));
    root.set("activeAlerts", alertCache == null ? Json.MAPPER.createArrayNode() : alertCache.deepCopy());
    root.set("metrics", metricsNode());
    return root;
  }

  private ArrayNode floorsNode(boolean full) {
    Map<String, Integer> onlineByZone = onlineByZone();
    ArrayNode floors = Json.MAPPER.createArrayNode();
    for (Map.Entry<Integer, List<String>> floor : zonesByFloor.entrySet()) {
      ObjectNode f = floors.addObject();
      f.put("floorNumber", floor.getKey());
      ArrayNode zones = f.putArray("zones");
      for (String zoneCode : floor.getValue()) {
        zones.add(zoneNode(zoneCode, onlineByZone.getOrDefault(zoneCode, 0)));
      }
    }
    return floors;
  }

  private ObjectNode zoneNode(String zoneCode, int online) {
    Counters c = counters.get(zoneCode);
    long received = c == null ? 0 : c.received.get();
    long acknowledged = c == null ? 0 : c.acknowledged.get();
    long failed = c == null ? 0 : c.failed.get();
    int total = devicesTotalByZone.getOrDefault(zoneCode, 0);
    ObjectNode z = Json.obj();
    z.put("zoneCode", zoneCode);
    z.put("devicesTotal", total);
    z.put("online", online);
    z.put("received", received);
    z.put("acknowledged", acknowledged);
    z.put("failed", failed);
    z.put("severity", ZoneStatus.deriveSeverity(total, online, received, acknowledged, failed));
    return z;
  }

  private Map<String, Integer> onlineByZone() {
    Map<String, Integer> out = new HashMap<>();
    for (ClientSession s : sessions.deviceSessions()) {
      String zone = s.zoneCode();
      if (zone != null) out.merge(zone, 1, Integer::sum);
    }
    return out;
  }

  private ObjectNode metricsNode() {
    ObjectNode m = Json.obj();
    m.put("connected", sessions.onlineDeviceCount());
    m.put("p50", metrics.p50());
    m.put("p95", metrics.p95());
    m.put("p99", metrics.p99());
    m.put("retryCount", metrics.retryCount());
    return m;
  }

  /**
   * Batched delta: only zones whose fields changed, metrics when changed,
   * activeAlerts as a FULL replacement list when changed. Returns the number
   * of subscribers the delta was written to (0 = nothing changed).
   */
  public int broadcastDelta() {
    rebuildIfDirty();
    ObjectNode current = snapshot();
    ObjectNode previous = lastBroadcast;
    ObjectNode delta = Json.obj();
    delta.put("version", 1);
    delta.put("type", "STATUS_DELTA");
    delta.put("buildingId", buildingId);
    delta.put("ts", current.get("ts").asText());

    boolean changed = false;
    if (previous == null) {
      // first broadcast after startup without subscribers: remember state only
      lastBroadcast = current;
      return 0;
    }
    ArrayNode changedFloors = Json.MAPPER.createArrayNode();
    ArrayNode prevFloors = (ArrayNode) previous.get("floors");
    ArrayNode curFloors = (ArrayNode) current.get("floors");
    for (int i = 0; i < curFloors.size(); i++) {
      ObjectNode curFloor = (ObjectNode) curFloors.get(i);
      ObjectNode prevFloor = (ObjectNode) prevFloors.get(i);
      ArrayNode curZones = (ArrayNode) curFloor.get("zones");
      ArrayNode prevZones = (ArrayNode) prevFloor.get("zones");
      ArrayNode dz = Json.MAPPER.createArrayNode();
      for (int j = 0; j < curZones.size(); j++) {
        if (!curZones.get(j).equals(prevZones.get(j))) {
          dz.add(curZones.get(j));
        }
      }
      if (!dz.isEmpty()) {
        ObjectNode df = changedFloors.addObject();
        df.put("floorNumber", curFloor.get("floorNumber").asInt());
        df.set("zones", dz);
      }
    }
    if (!changedFloors.isEmpty()) {
      delta.set("floors", changedFloors);
      changed = true;
    }
    if (!current.get("metrics").equals(previous.get("metrics"))) {
      delta.set("metrics", current.get("metrics"));
      changed = true;
    }
    if (!current.get("activeAlerts").equals(previous.get("activeAlerts"))) {
      delta.set("activeAlerts", current.get("activeAlerts"));
      changed = true;
    }
    lastBroadcast = current;
    if (!changed) return 0;
    String text = Json.write(delta);
    int sent = 0;
    for (WsConnection conn : subscribers) {
      if (conn.sendText(text)) sent++;
      else unsubscribe(conn);
    }
    return sent;
  }

  // ---- subscribers -----------------------------------------------------------

  public void subscribe(WsConnection conn) {
    rebuildIfDirty();
    // Contract: snapshot is the FIRST frame a subscriber sees. Enqueue it before
    // adding to subscribers so a concurrent broadcastDelta cannot jump the queue
    // (review H1-H4 residual R1). Async writer preserves FIFO order.
    conn.sendText(Json.write(snapshot()));
    subscribers.add(conn);
  }

  private void rebuildIfDirty() {
    long target = alertsGeneration.get();
    if (cacheGeneration.get() == target) return;
    try {
      rebuildAlertCache();
      // Publish a newer generation during the rebuild? Keep the older cached
      // generation so the next cycle/subscribe rebuilds again (no lost signal).
      cacheGeneration.accumulateAndGet(target, (cur, seen) -> cur < seen ? seen : cur);
    } catch (SQLException e) {
      LOG.warn("alert cache rebuild failed: {}", e.toString()); // cacheGeneration stays old -> retry
    }
  }

  public void unsubscribe(WsConnection conn) {
    subscribers.remove(conn);
  }

  public int subscriberCount() { return subscribers.size(); }

  public void closeAll() {
    for (WsConnection conn : subscribers) {
      conn.close();
    }
    subscribers.clear();
  }

  // ---- active alerts -----------------------------------------------------------

  private void rebuildAlertCache() throws SQLException {
    ArrayNode arr = Json.MAPPER.createArrayNode();
    Map<String, Map<DeliveryState, Integer>> stats = store.activeAlertStats(buildingId);
    for (Alert alert : store.activeAlerts(buildingId)) {
      AlertRevision revision = alerts.findRevision(alert.id(), alert.currentRevision()).orElse(null);
      if (revision == null) continue;
      Map<DeliveryState, Integer> alertStat = stats.getOrDefault(alert.id(), Map.of());
      int failed = alertStat.getOrDefault(DeliveryState.FAILED, 0);
      int unacked = alertStat.getOrDefault(DeliveryState.PENDING, 0)
          + alertStat.getOrDefault(DeliveryState.SENT, 0)
          + alertStat.getOrDefault(DeliveryState.RETRY_SCHEDULED, 0);
      ObjectNode a = arr.addObject();
      a.put("alertId", alert.id());
      a.put("severity", alert.priority() == aegis.protocol.Priority.CRITICAL
          || alert.priority() == aegis.protocol.Priority.EMERGENCY ? "critical" : "warning");
      int floorNumber = 0;
      String zoneCode = "";
      JsonNode meta = parseMeta(revision.metadataJson());
      JsonNode scope = meta.path("scope");
      if (scope.hasNonNull("floorNumber")) floorNumber = scope.get("floorNumber").asInt();
      if (scope.hasNonNull("zoneCode")) zoneCode = scope.get("zoneCode").asText();
      if (zoneCode.isEmpty()) {
        // building/floor-wide alert: anchor to the first target device's zone
        List<String> deviceIds = alerts.targets(alert.id(), alert.currentRevision()).stream()
            .map(aegis.persistence.AlertTarget::deviceId).toList();
        for (String deviceId : deviceIds) {
          ServerStore.DeviceInfo info = deviceInfo.get(deviceId);
          if (info != null && info.zoneCode() != null) {
            floorNumber = info.floorNumber();
            zoneCode = info.zoneCode();
            break;
          }
        }
      }
      a.put("floorNumber", floorNumber);
      a.put("zoneCode", zoneCode);
      a.put("message", revision.title() + " · " + failed + " FAILED · " + unacked + " unacked");
      a.put("ts", Json.ts(alert.createdAt()));
    }
    alertCache = arr;
  }

  private static JsonNode parseMeta(String metadataJson) {
    try {
      JsonNode n = Json.MAPPER.readTree(metadataJson == null ? "{}" : metadataJson);
      return n.isObject() ? n : Json.obj();
    } catch (Exception e) {
      return Json.obj();
    }
  }
}
