package aegis.persistence;

import aegis.protocol.Priority;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository for alerts, alert_revisions and alert_targets
 * (UUID text ids, UTC ISO-8601 timestamps).
 */
public final class AlertRepo {
  private final Db db;
  private final Clock clock;

  public AlertRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public AlertRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Alert createDraft(String buildingId, String createdBy, String category,
                           Priority priority, java.time.Instant expiresAt) throws SQLException {
    Alert alert = new Alert(Sql.uuid(), buildingId, createdBy, category, priority,
        AlertState.DRAFT, 1, clock.instant(), expiresAt);
    try (Connection c = db.open()) {
      insertOn(c, alert);
    }
    return alert;
  }

  /** Insert within an existing transaction/connection (used by TransactionalPublisher). */
  public static void insertOn(Connection c, Alert a) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO alerts(id,building_id,created_by,category,priority,state,current_revision,created_at,expires_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, a.id());
      ps.setString(2, a.buildingId());
      ps.setString(3, a.createdBy());
      ps.setString(4, a.category());
      ps.setString(5, a.priority().name());
      ps.setString(6, a.state().name());
      ps.setInt(7, a.currentRevision());
      ps.setString(8, Sql.ts(a.createdAt()));
      ps.setString(9, Sql.ts(a.expiresAt()));
      ps.executeUpdate();
    }
  }

  public static void insertRevisionOn(Connection c, AlertRevision r) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO alert_revisions(alert_id,revision,title,body,metadata_json,created_at,created_by) "
            + "VALUES(?,?,?,?,?,?,?)")) {
      ps.setString(1, r.alertId());
      ps.setInt(2, r.revision());
      ps.setString(3, r.title());
      ps.setString(4, r.body());
      ps.setString(5, r.metadataJson());
      ps.setString(6, Sql.ts(r.createdAt()));
      ps.setString(7, r.createdBy());
      ps.executeUpdate();
    }
  }

  public static void insertTargetOn(Connection c, AlertTarget t) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO alert_targets(id,alert_id,revision,device_id) VALUES(?,?,?,?)")) {
      ps.setString(1, t.id());
      ps.setString(2, t.alertId());
      ps.setInt(3, t.revision());
      ps.setString(4, t.deviceId());
      ps.executeUpdate();
    }
  }

  public Optional<Alert> findById(String id) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM alerts WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapAlert(rs)) : Optional.empty();
      }
    }
  }

  public List<Alert> findByState(AlertState state) throws SQLException {
    List<Alert> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM alerts WHERE state=? ORDER BY created_at, id")) {
      ps.setString(1, state.name());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(mapAlert(rs));
        }
      }
    }
    return out;
  }

  public boolean updateState(String id, AlertState state) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("UPDATE alerts SET state=? WHERE id=?")) {
      ps.setString(1, state.name());
      ps.setString(2, id);
      return ps.executeUpdate() == 1;
    }
  }

  public boolean setCurrentRevision(String id, int revision) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE alerts SET current_revision=? WHERE id=?")) {
      ps.setInt(1, revision);
      ps.setString(2, id);
      return ps.executeUpdate() == 1;
    }
  }

  public AlertRevision addRevision(String alertId, int revision, String title, String body,
                                   String metadataJson, String createdBy) throws SQLException {
    AlertRevision r = new AlertRevision(alertId, revision, title, body, metadataJson,
        clock.instant(), createdBy);
    try (Connection c = db.open()) {
      insertRevisionOn(c, r);
    }
    return r;
  }

  public Optional<AlertRevision> findRevision(String alertId, int revision) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM alert_revisions WHERE alert_id=? AND revision=?")) {
      ps.setString(1, alertId);
      ps.setInt(2, revision);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapRevision(rs)) : Optional.empty();
      }
    }
  }

  public List<AlertRevision> revisions(String alertId) throws SQLException {
    List<AlertRevision> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM alert_revisions WHERE alert_id=? ORDER BY revision")) {
      ps.setString(1, alertId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(mapRevision(rs));
        }
      }
    }
    return out;
  }

  public AlertTarget addTarget(String alertId, int revision, String deviceId) throws SQLException {
    AlertTarget t = new AlertTarget(Sql.uuid(), alertId, revision, deviceId);
    try (Connection c = db.open()) {
      insertTargetOn(c, t);
    }
    return t;
  }

  /** Target device snapshot for one revision. */
  public List<AlertTarget> targets(String alertId, int revision) throws SQLException {
    List<AlertTarget> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM alert_targets WHERE alert_id=? AND revision=? ORDER BY device_id")) {
      ps.setString(1, alertId);
      ps.setInt(2, revision);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new AlertTarget(rs.getString("id"), rs.getString("alert_id"),
              rs.getInt("revision"), rs.getString("device_id")));
        }
      }
    }
    return out;
  }

  private static Alert mapAlert(ResultSet rs) throws SQLException {
    return new Alert(rs.getString("id"), rs.getString("building_id"), rs.getString("created_by"),
        rs.getString("category"), Priority.valueOf(rs.getString("priority")),
        AlertState.valueOf(rs.getString("state")), rs.getInt("current_revision"),
        Sql.instant(rs, "created_at"), Sql.instant(rs, "expires_at"));
  }

  private static AlertRevision mapRevision(ResultSet rs) throws SQLException {
    return new AlertRevision(rs.getString("alert_id"), rs.getInt("revision"),
        rs.getString("title"), rs.getString("body"), rs.getString("metadata_json"),
        Sql.instant(rs, "created_at"), rs.getString("created_by"));
  }
}
