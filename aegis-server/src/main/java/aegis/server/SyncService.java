package aegis.server;

import aegis.persistence.Alert;
import aegis.persistence.AlertRevision;
import aegis.persistence.Delivery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/**
 * Reconnect SYNC: the device receives the latest revision of every ACTIVE
 * alert it targets plus tombstones for cancelled/expired alerts (never
 * redisplayed). Unacked deliveries are resumed by the fanout engine.
 */
public final class SyncService {

  private final ServerStore store;
  private final FanoutEngine engine;
  private final Clock clock;

  public SyncService(ServerStore store, FanoutEngine engine, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.engine = Objects.requireNonNull(engine);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Builds the SYNC_RESULT payload for an authenticated device. */
  public ObjectNode syncResult(String deviceId) throws SQLException {
    ObjectNode payload = Json.obj();
    payload.put("serverTime", Json.ts(clock.instant()));
    payload.put("deviceId", deviceId);

    ArrayNode alertsNode = payload.putArray("alerts");
    for (ServerStore.DeviceAlert da : store.activeAlertsForDevice(deviceId)) {
      Alert alert = da.alert();
      AlertRevision revision = da.revision();
      Delivery delivery = da.delivery();
      ObjectNode a = alertsNode.addObject();
      a.put("alertId", alert.id());
      a.put("revision", revision.revision());
      a.put("deliveryId", delivery.id());
      a.put("deliveryState", delivery.state().name());
      a.put("buildingId", alert.buildingId());
      a.put("category", alert.category());
      a.put("priority", alert.priority().name());
      a.put("title", revision.title());
      a.put("instruction", revision.body());
      JsonNode meta = parseMeta(revision.metadataJson());
      a.put("simulation", meta.path("simulation").asBoolean(true));
      if (meta.hasNonNull("incidentId")) a.put("incidentId", meta.get("incidentId").asText());
      JsonNode scope = meta.path("scope");
      if (scope.hasNonNull("floorNumber")) a.put("floorNumber", scope.get("floorNumber").asInt());
      if (scope.hasNonNull("zoneCode")) a.put("zoneCode", scope.get("zoneCode").asText());
      if (alert.expiresAt() != null) a.put("expiresAt", Json.ts(alert.expiresAt()));
      a.put("createdAt", Json.ts(alert.createdAt()));
    }

    ArrayNode tombstones = payload.putArray("tombstones");
    for (ServerStore.Tombstone t : store.tombstonesForDevice(deviceId)) {
      ObjectNode node = tombstones.addObject();
      node.put("alertId", t.alertId());
      node.put("revision", t.revision());
      node.put("state", t.state().name());
    }

    engine.resumeDevice(deviceId);
    return payload;
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
