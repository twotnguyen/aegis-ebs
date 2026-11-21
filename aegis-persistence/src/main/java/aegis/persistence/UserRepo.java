package aegis.persistence;

import aegis.protocol.Role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Repository for users (UUID text ids, UTC ISO-8601 timestamps). */
public final class UserRepo {
  private final Db db;
  private final Clock clock;

  public UserRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public UserRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public User create(String username, String passwordHash, Role role) throws SQLException {
    User user = new User(Sql.uuid(), username, passwordHash, role, true, clock.instant());
    try (Connection c = db.open()) {
      insertOn(c, user);
    }
    return user;
  }

  /** Insert within an existing transaction/connection (used by composite writes). */
  public static void insertOn(Connection c, User u) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO users(id,username,password_hash,role,enabled,created_at) VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, u.id());
      ps.setString(2, u.username());
      ps.setString(3, u.passwordHash());
      ps.setString(4, u.role().name());
      ps.setInt(5, Sql.bool(u.enabled()));
      ps.setString(6, Sql.ts(u.createdAt()));
      ps.executeUpdate();
    }
  }

  public Optional<User> findById(String id) throws SQLException {
    return one("SELECT * FROM users WHERE id=?", id);
  }

  public Optional<User> findByUsername(String username) throws SQLException {
    return one("SELECT * FROM users WHERE username=?", username);
  }

  public List<User> list() throws SQLException {
    List<User> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM users ORDER BY username");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(map(rs));
      }
    }
    return out;
  }

  public boolean setEnabled(String id, boolean enabled) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("UPDATE users SET enabled=? WHERE id=?")) {
      ps.setInt(1, Sql.bool(enabled));
      ps.setString(2, id);
      return ps.executeUpdate() == 1;
    }
  }

  private Optional<User> one(String sql, String arg) throws SQLException {
    try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private static User map(ResultSet rs) throws SQLException {
    return new User(rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
        Role.valueOf(rs.getString("role")), rs.getInt("enabled") == 1,
        Sql.instant(rs, "created_at"));
  }
}
