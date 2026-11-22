package aegis.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository for deliveries, delivery_attempts and device_acks
 * (UUID text ids, UTC ISO-8601 timestamps).
 */
public final class DeliveryRepo {
  private final Db db;
  private final Clock clock;

  public DeliveryRepo(Db db) {
    this(db, Clock.systemUTC());
  }

  public DeliveryRepo(Db db, Clock clock) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Delivery create(String alertId, int revision, String deviceId) throws SQLException {
    Delivery d = new Delivery(Sql.uuid(), alertId, revision, deviceId,
        DeliveryState.PENDING, null, 0, clock.instant());
    try (Connection c = db.open()) {
      insertOn(c, d);
    }
    return d;
  }

  /** Insert within an existing transaction/connection (used by TransactionalPublisher). */
  public static void insertOn(Connection c, Delivery d) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO deliveries(id,alert_id,revision,device_id,state,next_retry_at,attempt_count,created_at) "
            + "VALUES(?,?,?,?,?,?,?,?)")) {
      ps.setString(1, d.id());
      ps.setString(2, d.alertId());
      ps.setInt(3, d.revision());
      ps.setString(4, d.deviceId());
      ps.setString(5, d.state().name());
      ps.setString(6, Sql.ts(d.nextRetryAt()));
      ps.setInt(7, d.attemptCount());
      ps.setString(8, Sql.ts(d.createdAt()));
      ps.executeUpdate();
    }
  }

  public Optional<Delivery> findById(String id) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement("SELECT * FROM deliveries WHERE id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  public List<Delivery> findByAlert(String alertId) throws SQLException {
    return query("SELECT * FROM deliveries WHERE alert_id=? ORDER BY created_at, id", alertId);
  }

  /**
   * Deliveries the scheduler still owns: PENDING, SENT, RETRY_SCHEDULED, FAILED.
   * Used by {@link RecoveryScanner} to rebuild the scheduler after a restart.
   */
  public List<Delivery> findActive() throws SQLException {
    List<Delivery> out = new ArrayList<>();
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "SELECT * FROM deliveries WHERE state IN ('PENDING','SENT','RETRY_SCHEDULED','FAILED') "
                 + "ORDER BY created_at, id");
         ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(map(rs));
      }
    }
    return out;
  }

  public boolean updateState(String id, DeliveryState state, Instant nextRetryAt, int attemptCount)
      throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE deliveries SET state=?, next_retry_at=?, attempt_count=? WHERE id=?")) {
      ps.setString(1, state.name());
      ps.setString(2, Sql.ts(nextRetryAt));
      ps.setInt(3, attemptCount);
      ps.setString(4, id);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Marks RETRY_SCHEDULED and increments attempt_count atomically, guarded to the
   * pre-ack states (PENDING/SENT/RETRY_SCHEDULED). A retry scheduled by the
   * offline/dispatch path can never regress a concurrent ACK (RECEIVED or higher)
   * back to RETRY_SCHEDULED nor inflate its attempt_count (H1, ACK-only-advances).
   *
   * @return {@code true} if the row was updated; {@code false} if unknown or
   *     already acked/terminal (benign no-op).
   */
  public boolean scheduleRetry(String id, Instant nextRetryAt) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE deliveries SET state='RETRY_SCHEDULED', next_retry_at=?, "
                 + "attempt_count=attempt_count+1 WHERE id=? "
                 + "AND state IN ('PENDING','SENT','RETRY_SCHEDULED')")) {
      ps.setString(1, Sql.ts(nextRetryAt));
      ps.setString(2, id);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * Marks FAILED (terminal for the retry budget) guarded to the pre-ack states
   * (PENDING/SENT/RETRY_SCHEDULED): a delivery the device already acknowledged
   * (RECEIVED/ACKNOWLEDGED) or that is already terminal must never be regressed to
   * FAILED by a late retry-budget check (H2, ACK-only-advances). attempt_count is
   * left untouched so the durable retry budget cannot shrink.
   *
   * @return {@code true} if the row was failed; {@code false} if unknown or
   *     already acked/terminal (benign no-op — caller must not count a failure).
   */
  public boolean markFailed(String id) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE deliveries SET state='FAILED', next_retry_at=NULL WHERE id=? "
                 + "AND state IN ('PENDING','SENT','RETRY_SCHEDULED')")) {
      ps.setString(1, id);
      return ps.executeUpdate() == 1;
    }
  }

  public DeliveryAttempt recordAttempt(String deliveryId, String messageId, String outcome)
      throws SQLException {
    DeliveryAttempt attempt = new DeliveryAttempt(Sql.uuid(), deliveryId, messageId,
        clock.instant(), outcome);
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO delivery_attempts(id,delivery_id,message_id,attempted_at,outcome) VALUES(?,?,?,?,?)")) {
      ps.setString(1, attempt.id());
      ps.setString(2, attempt.deliveryId());
      ps.setString(3, attempt.messageId());
      ps.setString(4, Sql.ts(attempt.attemptedAt()));
      ps.setString(5, attempt.outcome());
      ps.executeUpdate();
    }
    return attempt;
  }

  /**
   * Records a device ack and advances the delivery state monotonically
   * (ACK only advances state: RECEIVED &lt; ACKNOWLEDGED; CANCEL is terminal).
   * Ack row + state change commit together.
   *
   * <p>The delivery must belong to the given {@code (deviceId, alertId, revision)}:
   * a mismatch or unknown delivery rolls back and throws {@link SQLException},
   * so one device can never ack/cancel another device's delivery. Repeating an
   * ack of the same kind is an idempotent no-op (at-least-once transport), and
   * an ack whose target rank is not above the current state leaves the state
   * unchanged.
   *
   * @return {@code true} if a new device_acks row was inserted; {@code false}
   *     if this exact ack kind was already recorded (idempotent duplicate).
   *     Callers surface the flag as the wire {@code duplicate} field and the
   *     DUPLICATE audit outcome — the INSERT uses ON CONFLICT DO NOTHING, so
   *     duplicates do NOT throw (S-01 contract, docs/reviews/server-review.md).
   */
  public boolean recordAck(String deviceId, String alertId, int revision, String deliveryId,
                           AckKind kind, String userId) throws SQLException {
    DeviceAck ack = new DeviceAck(Sql.uuid(), deviceId, alertId, revision, deliveryId,
        kind, clock.instant(), userId);
    DeliveryState target = switch (kind) {
      case RECEIVED -> DeliveryState.RECEIVED;
      case USER -> DeliveryState.ACKNOWLEDGED;
      case CANCEL -> DeliveryState.CANCELLED;
    };
    int targetRank = rank(target);
    boolean inserted;
    try (Connection c = db.open()) {
      try (Statement s = c.createStatement()) {
        s.execute("BEGIN IMMEDIATE");
        try {
          // The ack may only target a delivery owned by this device/alert/revision;
          // reject mismatches before writing anything (cross-device ack guard).
          try (PreparedStatement ps = c.prepareStatement(
              "SELECT device_id, alert_id, revision FROM deliveries WHERE id=?")) {
            ps.setString(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw new SQLException("recordAck: unknown delivery " + deliveryId);
              }
              if (!Objects.equals(deviceId, rs.getString(1))
                  || !Objects.equals(alertId, rs.getString(2))
                  || revision != rs.getInt(3)) {
                throw new SQLException("recordAck: delivery " + deliveryId
                    + " does not belong to device/alert/revision (" + deviceId + ", "
                    + alertId + ", " + revision + ")");
              }
            }
          }
          // Duplicate ack of the same kind is a benign no-op (at-least-once retries).
          try (PreparedStatement ps = c.prepareStatement(
              "INSERT INTO device_acks(id,device_id,alert_id,revision,delivery_id,ack_kind,acked_at,user_id) "
                  + "VALUES(?,?,?,?,?,?,?,?) "
                  + "ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING")) {
            ps.setString(1, ack.id());
            ps.setString(2, ack.deviceId());
            ps.setString(3, ack.alertId());
            ps.setInt(4, ack.revision());
            ps.setString(5, ack.deliveryId());
            ps.setString(6, ack.ackKind().name());
            ps.setString(7, Sql.ts(ack.ackedAt()));
            ps.setString(8, ack.userId());
            inserted = ps.executeUpdate() == 1;
          }
          try (PreparedStatement ps = c.prepareStatement(
              "UPDATE deliveries SET state=? WHERE id=? AND device_id=? AND alert_id=? AND revision=? AND "
                  + "CASE state WHEN 'PENDING' THEN 0 WHEN 'SENT' THEN 1 WHEN 'RETRY_SCHEDULED' THEN 1 "
                  + "WHEN 'RECEIVED' THEN 2 WHEN 'ACKNOWLEDGED' THEN 3 WHEN 'FAILED' THEN 3 "
                  + "WHEN 'EXPIRED' THEN 4 WHEN 'CANCELLED' THEN 4 END < ?")) {
            ps.setString(1, target.name());
            ps.setString(2, deliveryId);
            ps.setString(3, deviceId);
            ps.setString(4, alertId);
            ps.setInt(5, revision);
            ps.setInt(6, targetRank);
            // 0 rows = state already at/above target rank: monotonic no-op, not an error.
            ps.executeUpdate();
          }
          s.execute("COMMIT");
        } catch (SQLException | RuntimeException e) {
          try {
            s.execute("ROLLBACK");
          } catch (SQLException rollback) {
            e.addSuppressed(rollback);
          }
          throw e;
        }
      }
    }
    return inserted;
  }

  /**
   * Dispatcher-owned state transition, guarded to the pre-ack states
   * (PENDING/SENT/RETRY_SCHEDULED): a late dispatch commit can never regress a
   * concurrent ACK (RECEIVED or higher) back to SENT/RETRY_SCHEDULED (S-02).
   * attempt_count/next_retry_at are written only when the guard passes.
   *
   * @return {@code true} if the row was updated; {@code false} if the delivery
   *     is unknown or already acked/terminal (benign no-op).
   */
  public boolean updateDispatchState(String id, DeliveryState state, Instant nextRetryAt,
                                     int attemptCount) throws SQLException {
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "UPDATE deliveries SET state=?, next_retry_at=?, attempt_count=? WHERE id=? "
                 + "AND state IN ('PENDING','SENT','RETRY_SCHEDULED')")) {
      ps.setString(1, state.name());
      ps.setString(2, Sql.ts(nextRetryAt));
      ps.setInt(3, attemptCount);
      ps.setString(4, id);
      return ps.executeUpdate() == 1;
    }
  }

  private static int rank(DeliveryState state) {
    return switch (state) {
      case PENDING -> 0;
      case SENT, RETRY_SCHEDULED -> 1;
      case RECEIVED -> 2;
      case ACKNOWLEDGED, FAILED -> 3;
      case EXPIRED, CANCELLED -> 4;
    };
  }

  private List<Delivery> query(String sql, String arg) throws SQLException {
    List<Delivery> out = new ArrayList<>();
    try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(map(rs));
        }
      }
    }
    return out;
  }

  private static Delivery map(ResultSet rs) throws SQLException {
    return new Delivery(rs.getString("id"), rs.getString("alert_id"), rs.getInt("revision"),
        rs.getString("device_id"), DeliveryState.valueOf(rs.getString("state")),
        Sql.instant(rs, "next_retry_at"), rs.getInt("attempt_count"), Sql.instant(rs, "created_at"));
  }
}
