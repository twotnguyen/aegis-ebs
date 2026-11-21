package aegis.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable local SQLite inbox for one device (SIMULATION/TRAINING).
 *
 * <p>Contract: an ALERT_PUSH is written here (committed, WAL + synchronous=FULL)
 * BEFORE ACK_RECEIVED goes on the wire; ACK_USER is a separate, human-gesture
 * column set only when the user presses Acknowledge. Cancel tombstones prevent
 * stale redisplay. Rows survive restart; pending ACKs are re-sent after reconnect.
 *
 * <p>Follows aegis-persistence conventions: short-lived connections with pragmas
 * applied at open, manual BEGIN IMMEDIATE / COMMIT / ROLLBACK for multi-statement
 * writes (autoCommit stays true), monotonic revision guards so ACK only advances.
 */
public final class InboxStore implements AutoCloseable {

  /** Outcome of a durable store attempt. */
  public enum StoreResult { STORED, DUPLICATE, STALE }

  /** An ACK that is durably owed but not yet confirmed sent. userId null => ACK_RECEIVED. */
  public record PendingAck(String alertId, long revision, String deliveryId, String messageId, String userId) {}

  /**
   * An active alert plus its durable user-ack state — the redisplay source after
   * restart/reconnect, so the UI keeps showing ACK_USER gestures already recorded.
   */
  public record ActiveAlert(AlertRecord alert, boolean userAcked, String userId, String ackUserAt) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path dbPath;
  private final String deviceId;

  public InboxStore(Path dbPath) { this(dbPath, "local-device"); }

  public InboxStore(Path dbPath, String deviceId) {
    this.dbPath = dbPath.toAbsolutePath();
    this.deviceId = deviceId;
    try {
      if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
      try (Connection c = open(); Statement s = c.createStatement()) {
        s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS inbox(
              alert_id TEXT PRIMARY KEY,
              revision INTEGER NOT NULL,
              delivery_id TEXT,
              message_id TEXT,
              device_id TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              received_at TEXT NOT NULL,
              ack_received_sent_at TEXT,
              ack_user_at TEXT,
              ack_user_sent_at TEXT,
              user_id TEXT,
              cancelled INTEGER NOT NULL DEFAULT 0,
              cancel_revision INTEGER,
              cancel_reason TEXT,
              cancelled_at TEXT
            )""");
        s.executeUpdate("""
            CREATE TABLE IF NOT EXISTS inbox_meta(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )""");
      }
    } catch (IOException | SQLException ex) {
      throw new IllegalStateException("cannot initialize inbox at " + dbPath, ex);
    }
  }

  public Path dbPath() { return dbPath; }

  /**
   * Durably store an alert revision. Monotonic per alertId: newer revision replaces
   * (and resets ACK state so the new content is re-acknowledged), equal => DUPLICATE,
   * older => STALE. A revision newer than a cancel tombstone re-activates the alert.
   */
  public synchronized StoreResult store(AlertRecord a) {
    try (Connection c = open()) {
      begin(c);
      try {
        long[] cur = currentRevision(c, a.alertId());
        StoreResult result;
        if (cur == null) {
          try (PreparedStatement ps = c.prepareStatement("""
              INSERT INTO inbox(alert_id, revision, delivery_id, message_id, device_id,
                                payload_json, received_at, cancelled)
              VALUES(?,?,?,?,?,?,?,0)""")) {
            ps.setString(1, a.alertId());
            ps.setLong(2, a.revision());
            ps.setString(3, a.deliveryId());
            ps.setString(4, a.messageId());
            ps.setString(5, deviceId);
            ps.setString(6, MAPPER.writeValueAsString(a.toPayload()));
            ps.setString(7, a.receivedAt());
            ps.executeUpdate();
          }
          result = StoreResult.STORED;
        } else if (a.revision() > cur[0]) {
          try (PreparedStatement ps = c.prepareStatement("""
              UPDATE inbox SET revision=?, delivery_id=?, message_id=?, payload_json=?,
                     received_at=?, ack_received_sent_at=NULL, ack_user_at=NULL,
                     ack_user_sent_at=NULL, user_id=NULL,
                     cancelled=CASE WHEN ?>COALESCE(cancel_revision,-1) THEN 0 ELSE cancelled END
              WHERE alert_id=?""")) {
            ps.setLong(1, a.revision());
            ps.setString(2, a.deliveryId());
            ps.setString(3, a.messageId());
            ps.setString(4, MAPPER.writeValueAsString(a.toPayload()));
            ps.setString(5, a.receivedAt());
            ps.setLong(6, a.revision());
            ps.setString(7, a.alertId());
            ps.executeUpdate();
          }
          result = StoreResult.STORED;
        } else if (a.revision() == cur[0]) {
          result = StoreResult.DUPLICATE;
        } else {
          result = StoreResult.STALE;
        }
        commit(c);
        return result;
      } catch (Exception ex) {
        rollback(c, ex);
        throw ex;
      }
    } catch (SQLException | IOException ex) {
      throw new IllegalStateException("inbox store failed for " + a.alertId(), ex);
    }
  }

  /** Record that ACK_RECEIVED for this exact revision left the wire (only advances). */
  public synchronized void markAckReceivedSent(String alertId, long revision, String sentAt) {
    update("UPDATE inbox SET ack_received_sent_at=? WHERE alert_id=? AND revision=? AND ack_received_sent_at IS NULL",
        sentAt, alertId, revision);
  }

  /**
   * Durable human acknowledgement. Only succeeds for the current revision of a
   * non-cancelled alert that has not been user-acknowledged yet (ACK only advances).
   */
  public synchronized boolean markUserAck(String alertId, long revision, String userId, String ackAt) {
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement("""
        UPDATE inbox SET ack_user_at=?, user_id=?
        WHERE alert_id=? AND revision=? AND cancelled=0 AND ack_user_at IS NULL""")) {
      ps.setString(1, ackAt);
      ps.setString(2, userId);
      ps.setString(3, alertId);
      ps.setLong(4, revision);
      return ps.executeUpdate() > 0;
    } catch (SQLException ex) {
      throw new IllegalStateException("markUserAck failed for " + alertId, ex);
    }
  }

  public synchronized void markUserAckSent(String alertId, long revision, String sentAt) {
    update("UPDATE inbox SET ack_user_sent_at=? WHERE alert_id=? AND revision=? AND ack_user_sent_at IS NULL",
        sentAt, alertId, revision);
  }

  /**
   * Apply a CANCEL_PUSH tombstone. Accepted when revision >= stored alert revision and
   * newer than any previous cancel; unknown alerts get a tombstone-only row so a later
   * stale ALERT_PUSH cannot redisplay them. Returns true when newly applied.
   */
  public synchronized boolean applyCancel(String alertId, long revision, String deviceId,
                                          String reason, String cancelledAt) {
    try (Connection c = open()) {
      begin(c);
      try {
        long[] cur = currentRevision(c, alertId);
        boolean applied;
        if (cur == null) {
          try (PreparedStatement ps = c.prepareStatement("""
              INSERT INTO inbox(alert_id, revision, device_id, payload_json, received_at,
                                cancelled, cancel_revision, cancel_reason, cancelled_at)
              VALUES(?,?,?,?,?,1,?,?,?)""")) {
            ps.setString(1, alertId);
            ps.setLong(2, revision);
            ps.setString(3, deviceId);
            ps.setString(4, "{}");
            ps.setString(5, cancelledAt);
            ps.setLong(6, revision);
            ps.setString(7, reason);
            ps.setString(8, cancelledAt);
            ps.executeUpdate();
          }
          applied = true;
        } else if (revision >= cur[0] && revision > cur[1]) {
          try (PreparedStatement ps = c.prepareStatement("""
              UPDATE inbox SET cancelled=1, cancel_revision=?, cancel_reason=?, cancelled_at=?
              WHERE alert_id=?""")) {
            ps.setLong(1, revision);
            ps.setString(2, reason);
            ps.setString(3, cancelledAt);
            ps.setString(4, alertId);
            ps.executeUpdate();
          }
          applied = true;
        } else {
          applied = false;
        }
        commit(c);
        return applied;
      } catch (Exception ex) {
        rollback(c, ex);
        throw ex;
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("applyCancel failed for " + alertId, ex);
    }
  }

  /** Active (non-cancelled) alerts, newest first — the redisplay source after restart. */
  public synchronized List<AlertRecord> activeAlerts() {
    List<AlertRecord> out = new ArrayList<>();
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
        "SELECT payload_json, message_id, received_at FROM inbox WHERE cancelled=0 ORDER BY received_at DESC, alert_id")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(AlertRecord.fromPayload(MAPPER.readTree(rs.getString(1)),
              rs.getString(2), null, null, rs.getString(3)));
        }
      }
    } catch (SQLException | IOException ex) {
      throw new IllegalStateException("activeAlerts query failed", ex);
    }
    return out;
  }

  /**
   * Active alerts with their durable user-ack state (L2): redisplay after restart must
   * preserve ACK_USER gestures instead of resetting the column to false. Same ordering
   * and visibility rules as {@link #activeAlerts()}.
   */
  public synchronized List<ActiveAlert> activeAlertsWithAck() {
    List<ActiveAlert> out = new ArrayList<>();
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
        "SELECT payload_json, message_id, received_at, ack_user_at, user_id FROM inbox "
            + "WHERE cancelled=0 ORDER BY received_at DESC, alert_id")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          AlertRecord a = AlertRecord.fromPayload(MAPPER.readTree(rs.getString(1)),
              rs.getString(2), null, null, rs.getString(3));
          String ackUserAt = rs.getString(4);
          out.add(new ActiveAlert(a, ackUserAt != null, rs.getString(5), ackUserAt));
        }
      }
    } catch (SQLException | IOException ex) {
      throw new IllegalStateException("activeAlertsWithAck query failed", ex);
    }
    return out;
  }

  public synchronized Optional<AlertRecord> find(String alertId) {
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
        "SELECT payload_json, message_id, received_at, cancelled FROM inbox WHERE alert_id=?")) {
      ps.setString(1, alertId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        AlertRecord a = AlertRecord.fromPayload(MAPPER.readTree(rs.getString(1)),
            rs.getString(2), null, null, rs.getString(3));
        return rs.getInt(4) == 1 ? Optional.empty() : Optional.of(a);
      }
    } catch (SQLException | IOException ex) {
      throw new IllegalStateException("find failed for " + alertId, ex);
    }
  }

  public synchronized boolean isCancelled(String alertId) {
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
        "SELECT cancelled FROM inbox WHERE alert_id=?")) {
      ps.setString(1, alertId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) == 1;
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("isCancelled failed for " + alertId, ex);
    }
  }

  /** Stored-but-unconfirmed ACK_RECEIVED rows (crash between commit and send). */
  public synchronized List<PendingAck> pendingReceivedAcks() {
    return pending("SELECT alert_id, revision, delivery_id, message_id FROM inbox "
        + "WHERE cancelled=0 AND ack_received_sent_at IS NULL ORDER BY received_at, alert_id", false);
  }

  /** User-acknowledged but unconfirmed ACK_USER rows (offline gesture). */
  public synchronized List<PendingAck> pendingUserAcks() {
    return pending("SELECT alert_id, revision, delivery_id, message_id, user_id FROM inbox "
        + "WHERE cancelled=0 AND ack_user_at IS NOT NULL AND ack_user_sent_at IS NULL ORDER BY ack_user_at, alert_id", true);
  }

  /** alertId -> highest revision ever seen (including cancel tombstones), for SYNC_REQUEST. */
  public synchronized Map<String, Long> knownRevisions() {
    Map<String, Long> out = new LinkedHashMap<>();
    try (Connection c = open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT alert_id, MAX(revision, COALESCE(cancel_revision, 0)) FROM inbox GROUP BY alert_id ORDER BY alert_id")) {
      while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
    } catch (SQLException ex) {
      throw new IllegalStateException("knownRevisions query failed", ex);
    }
    return out;
  }

  public synchronized int count() {
    try (Connection c = open(); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM inbox")) {
      return rs.next() ? rs.getInt(1) : 0;
    } catch (SQLException ex) {
      throw new IllegalStateException("count failed", ex);
    }
  }

  @Override public synchronized void close() {
    // Short-lived connections: nothing pooled. Checkpoint WAL so the file is self-contained.
    try (Connection c = open(); Statement s = c.createStatement()) {
      s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
    } catch (SQLException ignored) {
      // best effort on shutdown
    }
  }

  // ---- internals ----

  private List<PendingAck> pending(String sql, boolean withUser) {
    List<PendingAck> out = new ArrayList<>();
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(new PendingAck(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            withUser ? rs.getString(5) : null));
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("pending ack query failed", ex);
    }
    return out;
  }

  private void update(String sql, String ts, String alertId, long revision) {
    try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, ts);
      ps.setString(2, alertId);
      ps.setLong(3, revision);
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new IllegalStateException("inbox update failed for " + alertId, ex);
    }
  }

  /** {revision, cancelRevision(-1 if none)} or null when the alert is unknown. */
  private static long[] currentRevision(Connection c, String alertId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT revision, COALESCE(cancel_revision, -1) FROM inbox WHERE alert_id=?")) {
      ps.setString(1, alertId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null;
      }
    }
  }

  private Connection open() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute("PRAGMA busy_timeout=5000");
    }
    return c;
  }

  private static void begin(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
  }

  private static void commit(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) { s.execute("COMMIT"); }
  }

  private static void rollback(Connection c, Exception cause) {
    try (Statement s = c.createStatement()) { s.execute("ROLLBACK"); }
    catch (SQLException ex) { cause.addSuppressed(ex); }
  }
}
