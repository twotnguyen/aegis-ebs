package aegis.server;

import aegis.core.TargetResolver;
import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AlertRevision;
import aegis.persistence.AlertState;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.TransactionalPublisher;
import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Alert lifecycle orchestration on top of the TransactionalPublisher:
 * publish (requestId-idempotent, target snapshot), update (new revision),
 * cancel (tombstone + CANCEL_PUSH). RBAC is enforced by the router BEFORE
 * these methods run; scope resolution uses the immutable TargetResolver.
 */
public final class AlertService {
  private static final Logger LOG = LoggerFactory.getLogger(AlertService.class);

  /** Outcome of a publish/update/cancel command for COMMAND_RESULT. */
  public record CommandOutcome(boolean ok, boolean duplicate, String alertId, int revision,
                               int deliveryCount, String code, String message) {
    static CommandOutcome ok(String alertId, int revision, int deliveryCount, boolean duplicate) {
      return new CommandOutcome(true, duplicate, alertId, revision, deliveryCount, null, null);
    }
    static CommandOutcome error(String code, String message) {
      return new CommandOutcome(false, false, null, 0, 0, code, message);
    }
  }

  public record Scope(String buildingId, Integer floorNumber, String floorId, String zoneCode) {}

  private final AlertRepo alerts;
  private final DeliveryRepo deliveries;
  private final ServerStore store;
  private final TransactionalPublisher publisher;
  private final FanoutEngine engine;
  private final TargetResolver targets;
  private final AuditService audit;
  private final MetricsRegistry metrics;
  private final StatusNotifier status;
  private final Clock clock;

  public AlertService(AlertRepo alerts, DeliveryRepo deliveries, ServerStore store,
                      TransactionalPublisher publisher, FanoutEngine engine, TargetResolver targets,
                      AuditService audit, MetricsRegistry metrics, StatusNotifier status, Clock clock) {
    this.alerts = Objects.requireNonNull(alerts);
    this.deliveries = Objects.requireNonNull(deliveries);
    this.store = Objects.requireNonNull(store);
    this.publisher = Objects.requireNonNull(publisher);
    this.engine = Objects.requireNonNull(engine);
    this.targets = Objects.requireNonNull(targets);
    this.audit = Objects.requireNonNull(audit);
    this.metrics = Objects.requireNonNull(metrics);
    this.status = Objects.requireNonNull(status);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Resolves a publish scope to a floorId (RBAC) + device snapshot. */
  public Scope resolveScope(String buildingId, Integer floorNumber, String zoneCode)
      throws SQLException {
    String floorId = null;
    if (floorNumber != null) {
      floorId = store.floorId(buildingId, floorNumber).orElse(null);
      if (floorId == null) {
        throw new Json.BadPayload("unknown floorNumber " + floorNumber + " in " + buildingId);
      }
    }
    // accept both the bare zone letter ("A") and the full zone_code ("F01-A")
    String normalizedZone = zoneCode;
    if (zoneCode != null && floorNumber != null && zoneCode.length() == 1) {
      normalizedZone = String.format("F%02d-%s", floorNumber, zoneCode.toUpperCase());
    }
    return new Scope(buildingId, floorNumber, floorId, normalizedZone);
  }

  public List<String> resolveTargets(Scope scope) {
    if (scope.floorId() != null && scope.zoneCode() != null) {
      return targets.resolveZone(scope.buildingId(), scope.floorId(), scope.zoneCode());
    }
    if (scope.floorId() != null) {
      return targets.resolveFloor(scope.buildingId(), scope.floorId());
    }
    return targets.resolveBuilding(scope.buildingId());
  }

  private static boolean samePayload(ServerStore.OperationRow op, String userId, String payloadHash) {
    return "PUBLISH".equals(op.kind())
        && Objects.equals(op.requestedBy(), userId)
        && Objects.equals(op.payloadHash(), payloadHash);
  }

  /**
   * Publishes an alert atomically. Duplicate requestId returns the ORIGINAL
   * alert (idempotent replay, no second fanout).
   */
  public CommandOutcome publish(String requestId, String userId, String incidentId, Scope scope,
                                String category, Priority priority, String title, String instruction,
                                boolean simulation, String urgency, String severity, String certainty,
                                Instant expiresAt, String correlationId) {
    try {
      List<String> deviceIds = resolveTargets(scope);
      if (deviceIds.isEmpty()) {
        return CommandOutcome.error("NO_TARGETS", "scope resolves to zero devices");
      }
      ObjectNode meta = Json.obj();
      ObjectNode scopeNode = meta.putObject("scope");
      scopeNode.put("buildingId", scope.buildingId());
      if (scope.floorNumber() != null) scopeNode.put("floorNumber", scope.floorNumber());
      if (scope.zoneCode() != null) scopeNode.put("zoneCode", scope.zoneCode());
      meta.put("simulation", simulation);
      meta.put("urgency", urgency);
      meta.put("severity", severity);
      meta.put("certainty", certainty);
      if (incidentId != null) meta.put("incidentId", incidentId);

      TransactionalPublisher.Request req = new TransactionalPublisher.Request(
          requestId, scope.buildingId(), userId, category, priority, title, instruction,
          Json.write(meta), expiresAt, deviceIds);
      String payloadHash = requestId == null ? null : TransactionalPublisher.payloadHash(req, deviceIds);
      Optional<ServerStore.OperationRow> existing = requestId == null
          ? Optional.empty() : store.findOperationByRequestId(requestId);
      if (existing.isPresent()) {
        ServerStore.OperationRow op = existing.get();
        if (!samePayload(op, userId, payloadHash)) {
          audit.append(userId, "PUBLISH_CONFLICT", scope.buildingId(), "CONFLICT", correlationId,
              Json.obj().put("requestId", requestId).put("alertId", op.alertId()));
          return CommandOutcome.error("CONFLICT", "requestId reused with a different payload");
        }
        audit.append(userId, "PUBLISH_DUPLICATE", scope.buildingId(), "DUPLICATE", correlationId,
            Json.obj().put("requestId", requestId).put("alertId", op.alertId()));
        return CommandOutcome.ok(op.alertId(), op.revision(), 0, true);
      }

      TransactionalPublisher.Result result;
      try {
        result = publisher.publish(req);
      } catch (SQLException duplicateRace) {
        // concurrent replay hit the UNIQUE request_id constraint: verify the winner
        Optional<ServerStore.OperationRow> winner = store.findOperationByRequestId(requestId);
        if (winner.isPresent()) {
          ServerStore.OperationRow op = winner.get();
          if (!samePayload(op, userId, payloadHash)) {
            return CommandOutcome.error("CONFLICT", "requestId reused with a different payload");
          }
          return CommandOutcome.ok(op.alertId(), op.revision(), 0, true);
        }
        throw duplicateRace;
      }
      if (incidentId != null) {
        store.linkIncidentAlert(incidentId, result.alert().id());
        store.insertIncidentEvent(incidentId, "ALERT_PUBLISHED", userId, correlationId,
            Json.write(Json.obj().put("alertId", result.alert().id())));
      }
      metrics.incPublished();
      audit.append(userId, "PUBLISH", scope.buildingId(), "OK", correlationId,
          Json.obj().put("requestId", requestId).put("alertId", result.alert().id())
              .put("priority", priority.name()).put("targets", deviceIds.size()));
      status.alertsChanged();
      return CommandOutcome.ok(result.alert().id(), 1, result.deliveries().size(), false);
    } catch (SQLException e) {
      LOG.warn("publish failed requestId={}", requestId, e);
      // NFR-017 (S-04): details stay in the log, never on the wire
      return CommandOutcome.error("INTERNAL", "publish failed");
    }
  }

  /** New revision of an ACTIVE alert; stale deliveries are superseded. */
  public CommandOutcome update(String requestId, String userId, String alertId, String title,
                               String instruction, String correlationId) {
    try {
      Optional<ServerStore.OperationRow> existing = store.findOperationByRequestId(requestId);
      if (existing.isPresent()) {
        ServerStore.OperationRow op = existing.get();
        return CommandOutcome.ok(op.alertId(), op.revision(), 0, true);
      }
      Optional<Alert> alertOpt = alerts.findById(alertId);
      if (alertOpt.isEmpty()) return CommandOutcome.error("NOT_FOUND", "unknown alert: " + alertId);
      Alert alert = alertOpt.get();
      if (alert.state() != AlertState.ACTIVE) {
        return CommandOutcome.error("CONFLICT", "alert not ACTIVE: " + alert.state());
      }
      int newRevision = alert.currentRevision() + 1;
      AlertRevision old = alerts.findRevision(alertId, alert.currentRevision()).orElseThrow();
      String newTitle = title == null ? old.title() : title;
      String newBody = instruction == null ? old.body() : instruction;
      // S-09: revision + current_revision + targets + deliveries + supersede +
      // operation commit as ONE transaction; enqueue only AFTER commit.
      List<Delivery> fresh = store.applyUpdate(alertId, newRevision, newTitle, newBody,
          old.metadataJson(), userId, requestId,
          sha256(alertId + "\n" + newRevision + "\n" + newTitle + "\n" + newBody));
      engine.recoverEnqueue(fresh); // continuation of an owned alert lifecycle
      audit.append(userId, "UPDATE", alert.buildingId(), "OK", correlationId,
          Json.obj().put("requestId", requestId).put("alertId", alertId).put("revision", newRevision));
      status.alertsChanged();
      return CommandOutcome.ok(alertId, newRevision, fresh.size(), false);
    } catch (SQLException e) {
      LOG.warn("update failed alertId={}", alertId, e);
      Optional<ServerStore.OperationRow> winner = operationWinner(requestId, e);
      if (winner.isPresent()) {
        return CommandOutcome.ok(winner.get().alertId(), winner.get().revision(), 0, true);
      }
      // NFR-017 (S-04): details stay in the log, never on the wire
      return CommandOutcome.error("INTERNAL", "update failed");
    }
  }

  /** Cancel tombstone: alert CANCELLED, deliveries CANCELLED, CANCEL_PUSH fanout. */
  public CommandOutcome cancel(String requestId, String userId, String alertId, String reason,
                               String correlationId) {
    try {
      Optional<ServerStore.OperationRow> existing = store.findOperationByRequestId(requestId);
      if (existing.isPresent()) {
        ServerStore.OperationRow op = existing.get();
        return CommandOutcome.ok(op.alertId(), op.revision(), 0, true);
      }
      Optional<Alert> alertOpt = alerts.findById(alertId);
      if (alertOpt.isEmpty()) return CommandOutcome.error("NOT_FOUND", "unknown alert: " + alertId);
      Alert alert = alertOpt.get();
      if (alert.state() == AlertState.CANCELLED) {
        return CommandOutcome.ok(alertId, alert.currentRevision(), 0, true);
      }
      if (alert.state() != AlertState.ACTIVE) {
        return CommandOutcome.error("CONFLICT", "alert not ACTIVE: " + alert.state());
      }
      // S-09: tombstones + operation commit as ONE transaction; fanout after commit.
      ServerStore.CancelResult result = store.applyCancel(alertId, alert.currentRevision(), userId,
          requestId, sha256(alertId + "\nCANCEL\n" + reason));
      engine.cancelAlert(alertId);
      ObjectNode payload = Json.obj();
      payload.put("alertId", alertId);
      payload.put("revision", alert.currentRevision());
      payload.put("operationId", result.operation().id());
      payload.put("reason", reason == null ? "" : reason);
      payload.put("simulation", true);
      Envelope cancelPush = new Envelope(Protocol.VERSION, "CANCEL_PUSH", Json.uuid(), requestId,
          Json.ts(clock.instant()), payload);
      engine.fanoutControl(alert.priority(), cancelPush, result.deviceIds());
      audit.append(userId, "CANCEL", alert.buildingId(), "OK", correlationId,
          Json.obj().put("requestId", requestId).put("alertId", alertId).put("reason", reason));
      status.alertsChanged();
      return CommandOutcome.ok(alertId, alert.currentRevision(), result.deviceIds().size(), false);
    } catch (SQLException e) {
      LOG.warn("cancel failed alertId={}", alertId, e);
      Optional<ServerStore.OperationRow> winner = operationWinner(requestId, e);
      if (winner.isPresent()) {
        return CommandOutcome.ok(winner.get().alertId(), winner.get().revision(), 0, true);
      }
      return CommandOutcome.error("INTERNAL", "cancel failed");
    }
  }

  /** Concurrent requestId replay: the UNIQUE race winner's operation, if any. */
  private Optional<ServerStore.OperationRow> operationWinner(String requestId, SQLException cause) {
    try {
      return store.findOperationByRequestId(requestId);
    } catch (SQLException lookupFailed) {
      cause.addSuppressed(lookupFailed);
      return Optional.empty();
    }
  }

  private static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
