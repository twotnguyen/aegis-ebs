package aegis.server;

import aegis.core.DeviceLocation;
import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AlertRevision;
import aegis.persistence.AlertState;
import aegis.persistence.Db;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.DeliveryState;
import aegis.persistence.RoleGrantRepo;
import aegis.persistence.UserRepo;
import aegis.persistence.DeviceRepo;
import aegis.protocol.Priority;
import aegis.protocol.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side SQL that complements the persistence repos without modifying
 * them: building/floor/zone topology, incidents, alert_operations idempotency
 * lookups (UPDATE/CANCEL), per-device sync views and metric samples.
 * Same conventions as aegis-persistence: short-lived connections, UUID text
 * ids, UTC ISO-8601 timestamps, manual BEGIN IMMEDIATE for composite writes.
 */
public final class ServerStore {

  public record DeviceInfo(String deviceId, String buildingId, String floorId,
                           int floorNumber, String zoneCode) {}

  public record IncidentRow(String id, String buildingId, String openedBy, String kind,
                            String summary, boolean simulation, String state,
                            Instant openedAt, Instant closedAt) {}

  public record OperationRow(String id, String alertId, int revision, String kind, String state) {}

  /** Active alert as seen by one device: latest revision content + its delivery. */
  public record DeviceAlert(Alert alert, AlertRevision revision, Delivery delivery) {}

  public record Tombstone(String alertId, int revision, AlertState state) {}

  private final Db db;
  private final Clock clock;
  private final AlertRepo alerts;
  private final DeliveryRepo deliveries;

  public ServerStore(Db db, Clock clock, AlertRepo alerts, DeliveryRepo deliveries) {
    this.db = Objects.requireNonNull(db);
    this.clock = Objects.requireNonNull(clock);
    this.alerts = Objects.requireNonNull(alerts);
    this.deliveries = Objects.requireNonNull(deliveries);
  }

  // ---- topology ----------------------------------------------------------

  public boolean buildingExists(String buildingId) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("SELECT 1 FROM buildings WHERE id=?")) {
      ps.setString(1, buildingId);
      try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
    }
  }

  public Optional<String> floorId(String buildingId, int floorNumber) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id FROM floors WHERE building_id=? AND floor_number=?")) {
      ps.setString(1, buildingId);
      ps.setInt(2, floorNumber);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  public Optional<DeviceInfo> deviceInfo(String deviceId) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT d.id, f.building_id, d.floor_id, f.floor_number, z.zone_code "
                 + "FROM devices d JOIN floors f ON f.id=d.floor_id "
                 + "LEFT JOIN zone_devices zd ON zd.device_id=d.id "
                 + "LEFT JOIN zones z ON z.id=zd.zone_id WHERE d.id=?")) {
      ps.setString(1, deviceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(new DeviceInfo(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getInt(4), rs.getString(5)));
      }
    }
  }

  /** Snapshot for TargetResolver: zoneId is the zone_code (unique per floor). */
  public List<DeviceLocation> deviceLocations() throws SQLException {
    List<DeviceLocation> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT d.id, f.building_id, d.floor_id, z.zone_code "
                 + "FROM devices d JOIN floors f ON f.id=d.floor_id "
                 + "LEFT JOIN zone_devices zd ON zd.device_id=d.id "
                 + "LEFT JOIN zones z ON z.id=zd.zone_id "
                 + "WHERE d.enabled=1 ORDER BY d.id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(new DeviceLocation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
      }
    }
    return out;
  }

  /** deviceId -> placement info for every enabled device (one query). */
  public Map<String, DeviceInfo> deviceInfoMap() throws SQLException {
    Map<String, DeviceInfo> out = new LinkedHashMap<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT d.id, f.building_id, d.floor_id, f.floor_number, z.zone_code "
                 + "FROM devices d JOIN floors f ON f.id=d.floor_id "
                 + "LEFT JOIN zone_devices zd ON zd.device_id=d.id "
                 + "LEFT JOIN zones z ON z.id=zd.zone_id WHERE d.enabled=1 ORDER BY d.id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.put(rs.getString(1), new DeviceInfo(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getInt(4), rs.getString(5)));
      }
    }
    return out;
  }

  /** Provisioned device count per zoneCode for the building (devicesTotal). */
  public Map<String, Integer> deviceCountByZone(String buildingId) throws SQLException {
    Map<String, Integer> out = new LinkedHashMap<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT z.zone_code, COUNT(zd.device_id) FROM zones z "
                 + "JOIN floors f ON f.id=z.floor_id "
                 + "LEFT JOIN zone_devices zd ON zd.zone_id=z.id "
                 + "WHERE f.building_id=? GROUP BY z.id ORDER BY f.floor_number, z.zone_code")) {
      ps.setString(1, buildingId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
      }
    }
    return out;
  }

  /** floorNumber -> ordered zoneCodes (the 10x4 dashboard grid). */
  public Map<Integer, List<String>> zonesByFloor(String buildingId) throws SQLException {
    Map<Integer, List<String>> out = new LinkedHashMap<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT f.floor_number, z.zone_code FROM zones z JOIN floors f ON f.id=z.floor_id "
                 + "WHERE f.building_id=? ORDER BY f.floor_number, z.zone_code")) {
      ps.setString(1, buildingId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(rs.getString(2));
        }
      }
    }
    return out;
  }

  // ---- incidents ---------------------------------------------------------

  public IncidentRow insertIncident(String buildingId, String openedBy, String kind,
                                    String summary, boolean simulation, String state)
      throws SQLException {
    String id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO incidents(id,building_id,opened_by,kind,summary,simulation,state,opened_at) "
                 + "VALUES(?,?,?,?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, buildingId);
      ps.setString(3, openedBy);
      ps.setString(4, kind);
      ps.setString(5, summary);
      ps.setInt(6, simulation ? 1 : 0);
      ps.setString(7, state);
      ps.setString(8, Json.ts(now));
      ps.executeUpdate();
    }
    return new IncidentRow(id, buildingId, openedBy, kind, summary, simulation, state, now, null);
  }

  public Optional<IncidentRow> findIncident(String id) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM incidents WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        String closed = rs.getString("closed_at");
        return Optional.of(new IncidentRow(rs.getString("id"), rs.getString("building_id"),
            rs.getString("opened_by"), rs.getString("kind"), rs.getString("summary"),
            rs.getInt("simulation") == 1, rs.getString("state"),
            Instant.parse(rs.getString("opened_at")), closed == null ? null : Instant.parse(closed)));
      }
    }
  }

  public boolean updateIncidentState(String id, String state, Instant closedAt) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE incidents SET state=?, closed_at=? WHERE id=?")) {
      ps.setString(1, state);
      ps.setString(2, closedAt == null ? null : Json.ts(closedAt));
      ps.setString(3, id);
      return ps.executeUpdate() == 1;
    }
  }

  public void insertIncidentEvent(String incidentId, String eventKind, String actorId,
                                  String correlationId, String payloadJson) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO incident_events(id,incident_id,occurred_at,event_kind,actor_id,correlation_id,payload_json) "
                 + "VALUES(?,?,?,?,?,?,?)")) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.setString(2, incidentId);
      ps.setString(3, Json.ts(clock.instant()));
      ps.setString(4, eventKind);
      ps.setString(5, actorId);
      ps.setString(6, correlationId);
      ps.setString(7, payloadJson);
      ps.executeUpdate();
    }
  }

  public void linkIncidentAlert(String incidentId, String alertId) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT OR IGNORE INTO incident_alerts(incident_id,alert_id) VALUES(?,?)")) {
      ps.setString(1, incidentId);
      ps.setString(2, alertId);
      ps.executeUpdate();
    }
  }

  // ---- alert_operations (idempotency for UPDATE/CANCEL; PUBLISH via publisher) ----

  public Optional<OperationRow> findOperationByRequestId(String requestId) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id,alert_id,revision,operation_kind,state FROM alert_operations WHERE request_id=?")) {
      ps.setString(1, requestId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(new OperationRow(rs.getString(1), rs.getString(2), rs.getInt(3),
            rs.getString(4), rs.getString(5)));
      }
    }
  }

  public OperationRow insertOperation(String alertId, int revision, String kind, String requestedBy,
                                      String requestId, String payloadHash) throws SQLException {
    String id = UUID.randomUUID().toString();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO alert_operations(id,alert_id,revision,operation_kind,requested_by,requested_at,"
                 + "request_id,payload_hash,state) VALUES(?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, alertId);
      ps.setInt(3, revision);
      ps.setString(4, kind);
      ps.setString(5, requestedBy);
      ps.setString(6, Json.ts(clock.instant()));
      ps.setString(7, requestId);
      ps.setString(8, payloadHash);
      ps.setString(9, "DONE");
      ps.executeUpdate();
    }
    return new OperationRow(id, alertId, revision, kind, "DONE");
  }

  // ---- alert views -------------------------------------------------------

  public List<Alert> activeAlerts(String buildingId) throws SQLException {
    List<Alert> out = new ArrayList<>();
    String now = Json.ts(clock.instant());
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id FROM alerts WHERE building_id=? AND state='ACTIVE' "
                 + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at, id")) {
      ps.setString(1, buildingId);
      ps.setString(2, now);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          alerts.findById(rs.getString(1)).ifPresent(out::add);
        }
      }
    }
    return out;
  }

  /** Marks overdue ACTIVE alerts EXPIRED (tombstone); returns their ids. */
  public List<String> expireOverdue() throws SQLException {
    String now = Json.ts(clock.instant());
    List<String> ids = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id FROM alerts WHERE state='ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?")) {
      ps.setString(1, now);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) ids.add(rs.getString(1));
      }
    }
    for (String id : ids) {
      alerts.updateState(id, AlertState.EXPIRED);
    }
    return ids;
  }

  /** ACTIVE alerts targeting the device, with latest revision + delivery row. */
  public List<DeviceAlert> activeAlertsForDevice(String deviceId) throws SQLException {
    List<DeviceAlert> out = new ArrayList<>();
    String now = Json.ts(clock.instant());
    List<String> alertIds = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT DISTINCT a.id FROM alerts a JOIN alert_targets t ON t.alert_id=a.id "
                 + "WHERE t.device_id=? AND a.state='ACTIVE' "
                 + "AND (a.expires_at IS NULL OR a.expires_at > ?) ORDER BY a.id")) {
      ps.setString(1, deviceId);
      ps.setString(2, now);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) alertIds.add(rs.getString(1));
      }
    }
    for (String alertId : alertIds) {
      Optional<Alert> alert = alerts.findById(alertId);
      if (alert.isEmpty()) continue;
      int rev = alert.get().currentRevision();
      Optional<AlertRevision> revision = alerts.findRevision(alertId, rev);
      Optional<Delivery> delivery = deliveryFor(alertId, rev, deviceId);
      if (revision.isPresent() && delivery.isPresent()) {
        out.add(new DeviceAlert(alert.get(), revision.get(), delivery.get()));
      }
    }
    return out;
  }

  /** Cancelled/expired alerts the device once targeted (never redisplayed). */
  public List<Tombstone> tombstonesForDevice(String deviceId) throws SQLException {
    List<Tombstone> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT DISTINCT a.id, a.current_revision, a.state FROM alerts a "
                 + "JOIN alert_targets t ON t.alert_id=a.id "
                 + "WHERE t.device_id=? AND a.state IN ('CANCELLED','EXPIRED') ORDER BY a.id")) {
      ps.setString(1, deviceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new Tombstone(rs.getString(1), rs.getInt(2), AlertState.valueOf(rs.getString(3))));
        }
      }
    }
    return out;
  }

  public Optional<Delivery> deliveryFor(String alertId, int revision, String deviceId)
      throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id FROM deliveries WHERE alert_id=? AND revision=? AND device_id=?")) {
      ps.setString(1, alertId);
      ps.setInt(2, revision);
      ps.setString(3, deviceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return deliveries.findById(rs.getString(1));
      }
    }
  }

  public List<Delivery> activeDeliveriesForDevice(String deviceId) throws SQLException {
    List<Delivery> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT id FROM deliveries WHERE device_id=? "
                 + "AND state IN ('PENDING','SENT','RETRY_SCHEDULED','FAILED') ORDER BY created_at, id")) {
      ps.setString(1, deviceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          deliveries.findById(rs.getString(1)).ifPresent(out::add);
        }
      }
    }
    return out;
  }

  public Map<DeliveryState, Integer> alertStats(String alertId) throws SQLException {
    Map<DeliveryState, Integer> out = new LinkedHashMap<>();
    for (DeliveryState s : DeliveryState.values()) out.put(s, 0);
    for (Delivery d : deliveries.findByAlert(alertId)) {
      out.merge(d.state(), 1, Integer::sum);
    }
    return out;
  }

  /** Delivery state counts for ALL active alerts of a building in ONE query. */
  public Map<String, Map<DeliveryState, Integer>> activeAlertStats(String buildingId)
      throws SQLException {
    Map<String, Map<DeliveryState, Integer>> out = new LinkedHashMap<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT d.alert_id, d.state, COUNT(*) FROM deliveries d "
                 + "JOIN alerts a ON a.id=d.alert_id "
                 + "WHERE a.building_id=? AND a.state='ACTIVE' GROUP BY d.alert_id, d.state")) {
      ps.setString(1, buildingId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
              .put(DeliveryState.valueOf(rs.getString(2)), rs.getInt(3));
        }
      }
    }
    return out;
  }

  // ---- metrics samples ----------------------------------------------------

  public void insertMetricSample(String name, double value, String unit, String tagsJson)
      throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO metrics_samples(id,recorded_at,metric_name,metric_value,unit,tags_json) "
                 + "VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.setString(2, Json.ts(clock.instant()));
      ps.setString(3, name);
      ps.setDouble(4, value);
      ps.setString(5, unit);
      ps.setString(6, tagsJson);
      ps.executeUpdate();
    }
  }

  // ---- demo world seeding --------------------------------------------------

  /**
   * Idempotent demo seed: building AEGIS-BLD-A, floors 1..10, zones A..D per
   * floor, {@code devicesPerZone} devices per zone, demo operators with grants.
   * All credentials are Argon2id hashes (device credential hashed once, shared
   * by the demo fleet — lab use only).
   */
  public void seedWorld(String buildingId, int devicesPerZone, PasswordHasher hasher)
      throws SQLException {
    if (buildingExists(buildingId)) return;
    String deviceHash = hasher.hash("device-demo-secret");
    Instant now = clock.instant();
    try (Connection c = db.open(); Statement s = c.createStatement()) {
      s.execute("BEGIN IMMEDIATE");
      try {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO buildings(id,code,name) VALUES(?,?,?)")) {
          ps.setString(1, buildingId);
          ps.setString(2, "A");
          ps.setString(3, "AEGIS Tower A");
          ps.executeUpdate();
        }
        String[] letters = {"A", "B", "C", "D"};
        String floor7Id = null;
        for (int f = 1; f <= 10; f++) {
          String floorId = UUID.randomUUID().toString();
          if (f == 7) floor7Id = floorId;
          try (PreparedStatement ps = c.prepareStatement(
              "INSERT INTO floors(id,building_id,floor_number,label) VALUES(?,?,?,?)")) {
            ps.setString(1, floorId);
            ps.setString(2, buildingId);
            ps.setInt(3, f);
            ps.setString(4, "Floor " + f);
            ps.executeUpdate();
          }
          for (String letter : letters) {
            String zoneCode = String.format("F%02d-%s", f, letter);
            String zoneId = UUID.randomUUID().toString();
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO zones(id,floor_id,zone_code,label) VALUES(?,?,?,?)")) {
              ps.setString(1, zoneId);
              ps.setString(2, floorId);
              ps.setString(3, zoneCode);
              ps.setString(4, "Zone " + zoneCode);
              ps.executeUpdate();
            }
            for (int d = 1; d <= devicesPerZone; d++) {
              String deviceId = UUID.randomUUID().toString();
              String code = String.format("DEV-A-F%02d-%s-%02d", f, letter, d);
              try (PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO devices(id,floor_id,device_code,credential_hash,enabled,enrolled_at) "
                      + "VALUES(?,?,?,?,1,?)")) {
                ps.setString(1, deviceId);
                ps.setString(2, floorId);
                ps.setString(3, code);
                ps.setString(4, deviceHash);
                ps.setString(5, Json.ts(now));
                ps.executeUpdate();
              }
              try (PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO zone_devices(zone_id,device_id,assigned_at) VALUES(?,?,?)")) {
                ps.setString(1, zoneId);
                ps.setString(2, deviceId);
                ps.setString(3, Json.ts(now));
                ps.executeUpdate();
              }
            }
          }
        }
        // Demo operators (Argon2id hashes).
        String adminId = seedUser(c, "admin", hasher.hash("admin-demo-secret"), Role.SYSTEM_ADMIN, now);
        String secopsId = seedUser(c, "secops", hasher.hash("secops-demo-secret"), Role.SECURITY_OPERATOR, now);
        String floor7UserId = seedUser(c, "floor7", hasher.hash("floor7-demo-secret"), Role.FLOOR_MANAGER, now);
        seedUser(c, "employee", hasher.hash("employee-demo-secret"), Role.EMPLOYEE, now);
        seedGrant(c, secopsId, buildingId, null, now);
        seedGrant(c, floor7UserId, buildingId, floor7Id, now);
        s.execute("COMMIT");
      } catch (SQLException | RuntimeException e) {
        try { s.execute("ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
        throw e;
      }
    }
  }

  private static String seedUser(Connection c, String username, String hash, Role role, Instant now)
      throws SQLException {
    String id = UUID.randomUUID().toString();
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO users(id,username,password_hash,role,enabled,created_at) VALUES(?,?,?,?,1,?)")) {
      ps.setString(1, id);
      ps.setString(2, username);
      ps.setString(3, hash);
      ps.setString(4, role.name());
      ps.setString(5, Json.ts(now));
      ps.executeUpdate();
    }
    return id;
  }

  private static void seedGrant(Connection c, String userId, String buildingId, String floorId,
                                Instant now) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO role_grants(id,user_id,building_id,floor_id,scope,granted_at) VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.setString(2, userId);
      ps.setString(3, buildingId);
      ps.setString(4, floorId);
      ps.setString(5, "PUBLISH");
      ps.setString(6, Json.ts(now));
      ps.executeUpdate();
    }
  }
}
