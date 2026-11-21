package aegis.persistence;

import aegis.protocol.Priority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Publishes an alert atomically:
 * {@code BEGIN IMMEDIATE -> insert alert + alert_revision + alert_targets
 * snapshot + deliveries PENDING (+ alert_operations idempotency record) -> COMMIT}.
 * The {@link DeliveryEnqueuer} is invoked ONLY after a successful commit; on any
 * failure the whole transaction rolls back and nothing is enqueued. If the
 * enqueuer itself fails after commit, the rows stay durable and
 * {@link RecoveryScanner} rebuilds the scheduler on restart.
 */
public final class TransactionalPublisher {
  /** Publish command. requestId nullable (skip idempotency record); expiresAt nullable. */
  public record Request(String requestId, String buildingId, String createdBy, String category,
                        Priority priority, String title, String body, String metadataJson,
                        Instant expiresAt, List<String> deviceIds) {}

  /** Committed outcome: the alert, its revision-1 content and the PENDING deliveries. */
  public record Result(Alert alert, AlertRevision revision, List<Delivery> deliveries) {}

  private final Db db;
  private final Clock clock;
  private final DeliveryEnqueuer enqueuer;

  public TransactionalPublisher(Db db, DeliveryEnqueuer enqueuer) {
    this(db, Clock.systemUTC(), enqueuer);
  }

  public TransactionalPublisher(Db db, Clock clock, DeliveryEnqueuer enqueuer) {
    this.db = Objects.requireNonNull(db, "db");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.enqueuer = Objects.requireNonNull(enqueuer, "enqueuer");
  }

  public Result publish(Request req) throws SQLException {
    Objects.requireNonNull(req, "req");
    Objects.requireNonNull(req.buildingId(), "buildingId");
    Objects.requireNonNull(req.createdBy(), "createdBy");
    Objects.requireNonNull(req.category(), "category");
    Objects.requireNonNull(req.priority(), "priority");
    Objects.requireNonNull(req.title(), "title");
    Objects.requireNonNull(req.body(), "body");
    Objects.requireNonNull(req.metadataJson(), "metadataJson");
    Objects.requireNonNull(req.deviceIds(), "deviceIds");
    for (String deviceId : req.deviceIds()) {
      if (deviceId == null) {
        throw new IllegalArgumentException("target device id must not be null");
      }
    }
    List<String> deviceIds = List.copyOf(new LinkedHashSet<>(req.deviceIds()));
    if (deviceIds.isEmpty()) {
      throw new IllegalArgumentException("publish requires at least one target device");
    }

    Instant now = clock.instant();
    Alert alert = new Alert(Sql.uuid(), req.buildingId(), req.createdBy(), req.category(),
        req.priority(), AlertState.ACTIVE, 1, now, req.expiresAt());
    AlertRevision revision = new AlertRevision(alert.id(), 1, req.title(), req.body(),
        req.metadataJson(), now, req.createdBy());
    List<AlertTarget> targets = new ArrayList<>();
    List<Delivery> deliveries = new ArrayList<>();
    for (String deviceId : deviceIds) {
      targets.add(new AlertTarget(Sql.uuid(), alert.id(), 1, deviceId));
      deliveries.add(new Delivery(Sql.uuid(), alert.id(), 1, deviceId,
          DeliveryState.PENDING, null, 0, now));
    }

    try (Connection c = db.open(); Statement s = c.createStatement()) {
      s.execute("BEGIN IMMEDIATE");
      try {
        AlertRepo.insertOn(c, alert);
        AlertRepo.insertRevisionOn(c, revision);
        for (AlertTarget target : targets) {
          AlertRepo.insertTargetOn(c, target);
        }
        for (Delivery delivery : deliveries) {
          DeliveryRepo.insertOn(c, delivery);
        }
        if (req.requestId() != null) {
          insertOperation(c, alert, req, deviceIds, now);
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

    // Only after COMMIT: hand the durable PENDING deliveries to the scheduler.
    enqueuer.enqueue(List.copyOf(deliveries));
    return new Result(alert, revision, List.copyOf(deliveries));
  }

  private static void insertOperation(Connection c, Alert alert, Request req,
                                      List<String> deviceIds, Instant now) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO alert_operations(id,alert_id,revision,operation_kind,requested_by,requested_at,"
            + "request_id,payload_hash,state) VALUES(?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, Sql.uuid());
      ps.setString(2, alert.id());
      ps.setInt(3, 1);
      ps.setString(4, "PUBLISH");
      ps.setString(5, req.createdBy());
      ps.setString(6, Sql.ts(now));
      ps.setString(7, req.requestId());
      ps.setString(8, payloadHash(req, deviceIds));
      ps.setString(9, "DONE");
      ps.executeUpdate();
    }
  }

  private static String payloadHash(Request req, List<String> deviceIds) {
    String canonical = req.title() + "\n" + req.body() + "\n" + req.metadataJson() + "\n"
        + String.join(",", deviceIds);
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
