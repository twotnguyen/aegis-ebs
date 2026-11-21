package aegis.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Repository for devices (UUID text ids, UTC ISO-8601 timestamps). */
public final class DeviceRepo {
  private final Db db;
  private final Clock clock;

  public DeviceRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public DeviceRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Device enroll(String floorId, String deviceCode, String credentialHash) throws SQLException {
    Device device = new Device(Sql.uuid(), floorId, deviceCode, credentialHash, true, clock.instant());
    try (Connection c = db.open()) {
      insertOn(c, device);
    }
    return device;
  }

  /** Insert within an existing transaction/connection (used by composite writes). */
  public static void insertOn(Connection c, Device d) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO devices(id,floor_id,device_code,credential_hash,enabled,enrolled_at) VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, d.id());
      ps.setString(2, d.floorId());
      ps.setString(3, d.deviceCode());
      ps.setString(4, d.credentialHash());
      ps.setInt(5, Sql.bool(d.enabled()));
      ps.setString(6, Sql.ts(d.enrolledAt()));
      ps.executeUpdate();
    }
  }

  public Optional<Device> findById(String id) throws SQLException {
    return one("SELECT * FROM devices WHERE id=?", id);
  }

  public Optional<Device> findByCode(String deviceCode) throws SQLException {
    return one("SELECT * FROM devices WHERE device_code=?", deviceCode);
  }

  public List<Device> listByFloor(String floorId) throws SQLException {
    return list("SELECT * FROM devices WHERE floor_id=? ORDER BY device_code", floorId);
  }

  public List<Device> listEnabled() throws SQLException {
    return list("SELECT * FROM devices WHERE enabled=1 ORDER BY device_code", null);
  }

  public boolean setEnabled(String id, boolean enabled) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("UPDATE devices SET enabled=? WHERE id=?")) {
      ps.setInt(1, Sql.bool(enabled));
      ps.setString(2, id);
      return ps.executeUpdate() == 1;
    }
  }

  private Optional<Device> one(String sql, String arg) throws SQLException {
    try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private List<Device> list(String sql, String arg) throws SQLException {
    List<Device> out = new ArrayList<>();
    try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      if (arg != null) {
        ps.setString(1, arg);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  private static Device map(ResultSet rs) throws SQLException {
    return new Device(rs.getString("id"), rs.getString("floor_id"), rs.getString("device_code"),
        rs.getString("credential_hash"), rs.getInt("enabled") == 1, Sql.instant(rs, "enrolled_at"));
  }
}
