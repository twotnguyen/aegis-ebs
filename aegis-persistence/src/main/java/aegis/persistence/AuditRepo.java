package aegis.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Repository for audit_events (UUID text ids, UTC ISO-8601 timestamps). */
public final class AuditRepo {
  private final Db db;
  private final Clock clock;

  public AuditRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public AuditRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public AuditEvent append(String actorId, String action, String scope, String outcome,
                           String correlationId, String payloadJson) throws SQLException {
    AuditEvent event = new AuditEvent(Sql.uuid(), clock.instant(), actorId, action, scope,
        outcome, correlationId, payloadJson);
    try (Connection c = db.open()) {
      insertOn(c, event);
    }
    return event;
  }

  /** Insert within an existing transaction/connection (used by composite writes). */
  public static void insertOn(Connection c, AuditEvent e) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO audit_events(id,occurred_at,actor_id,action,scope,outcome,correlation_id,payload_json) "
            + "VALUES(?,?,?,?,?,?,?,?)")) {
      ps.setString(1, e.id());
      ps.setString(2, Sql.ts(e.occurredAt()));
      ps.setString(3, e.actorId());
      ps.setString(4, e.action());
      ps.setString(5, e.scope());
      ps.setString(6, e.outcome());
      ps.setString(7, e.correlationId());
      ps.setString(8, e.payloadJson());
      ps.executeUpdate();
    }
  }

  /** Most recent events first (insertion order breaks equal-timestamp ties). */
  public List<AuditEvent> recent(int limit) throws SQLException {
    List<AuditEvent> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM audit_events ORDER BY occurred_at DESC, rowid DESC LIMIT ?")) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  public List<AuditEvent> findByCorrelation(String correlationId) throws SQLException {
    List<AuditEvent> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM audit_events WHERE correlation_id=? ORDER BY occurred_at, rowid")) {
      ps.setString(1, correlationId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  private static AuditEvent map(ResultSet rs) throws SQLException {
    return new AuditEvent(rs.getString("id"), Sql.instant(rs, "occurred_at"),
        rs.getString("actor_id"), rs.getString("action"), rs.getString("scope"),
        rs.getString("outcome"), rs.getString("correlation_id"), rs.getString("payload_json"));
  }
}
