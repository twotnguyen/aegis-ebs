package aegis.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Repository for role_grants (UUID text ids, UTC ISO-8601 timestamps). */
public final class RoleGrantRepo {
  private final Db db;
  private final Clock clock;

  public RoleGrantRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public RoleGrantRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** buildingId/floorId may be null for global/building-wide scopes. */
  public RoleGrant grant(String userId, String buildingId, String floorId, String scope)
      throws SQLException {
    RoleGrant grant = new RoleGrant(Sql.uuid(), userId, buildingId, floorId, scope, clock.instant());
    try (Connection c = db.open()) {
      insertOn(c, grant);
    }
    return grant;
  }

  /** Insert within an existing transaction/connection (used by composite writes). */
  public static void insertOn(Connection c, RoleGrant g) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO role_grants(id,user_id,building_id,floor_id,scope,granted_at) VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, g.id());
      ps.setString(2, g.userId());
      ps.setString(3, g.buildingId());
      ps.setString(4, g.floorId());
      ps.setString(5, g.scope());
      ps.setString(6, Sql.ts(g.grantedAt()));
      ps.executeUpdate();
    }
  }

  public List<RoleGrant> findByUser(String userId) throws SQLException {
    List<RoleGrant> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM role_grants WHERE user_id=? ORDER BY granted_at, id")) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  public boolean revoke(String id) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("DELETE FROM role_grants WHERE id=?")) {
      ps.setString(1, id);
      return ps.executeUpdate() == 1;
    }
  }

  private static RoleGrant map(ResultSet rs) throws SQLException {
    return new RoleGrant(rs.getString("id"), rs.getString("user_id"), rs.getString("building_id"),
        rs.getString("floor_id"), rs.getString("scope"), Sql.instant(rs, "granted_at"));
  }
}
