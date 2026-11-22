package aegis.server;

import aegis.core.DeviceLocation;
import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AckKind;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.Device;
import aegis.persistence.User;
import aegis.persistence.UserRepo;
import aegis.protocol.Envelope;
import aegis.protocol.MessageType;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import aegis.protocol.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Inbound EBSP/2.0 command router. Enforces the auth gate (only HELLO/AUTH_*
 * before authentication), the device/operator message split, and ScopedRBAC
 * on EVERY publish-class command. Replies are CRITICAL so control frames are
 * never starved by alert floods in the bounded writer queue.
 */
public final class MessageRouter {
  private static final Logger LOG = LoggerFactory.getLogger(MessageRouter.class);

  private final ServerConfig config;
  private final AuthService auth;
  private final RbacService rbac;
  private final AlertService alertService;
  private final IncidentService incidents;
  private final SyncService sync;
  private final FanoutEngine engine;
  private final SessionRegistry sessions;
  private final ServerStore store;
  private final AlertRepo alertRepo;
  private final DeliveryRepo deliveries;
  private final UserRepo users;
  private final AuditService audit;
  private final StatusFeedService status;
  private final Clock clock;

  public MessageRouter(ServerConfig config, AuthService auth, RbacService rbac,
                       AlertService alertService, IncidentService incidents, SyncService sync,
                       FanoutEngine engine, SessionRegistry sessions, ServerStore store,
                       AlertRepo alertRepo, DeliveryRepo deliveries, UserRepo users,
                       AuditService audit, StatusFeedService status, Clock clock) {
    this.config = Objects.requireNonNull(config);
    this.auth = Objects.requireNonNull(auth);
    this.rbac = Objects.requireNonNull(rbac);
    this.alertService = Objects.requireNonNull(alertService);
    this.incidents = Objects.requireNonNull(incidents);
    this.sync = Objects.requireNonNull(sync);
    this.engine = Objects.requireNonNull(engine);
    this.sessions = Objects.requireNonNull(sessions);
    this.store = Objects.requireNonNull(store);
    this.alertRepo = Objects.requireNonNull(alertRepo);
    this.deliveries = Objects.requireNonNull(deliveries);
    this.users = Objects.requireNonNull(users);
    this.audit = Objects.requireNonNull(audit);
    this.status = Objects.requireNonNull(status);
    this.clock = Objects.requireNonNull(clock);
  }

  public void route(ClientSession session, Envelope env) {
    MessageType type;
    try {
      type = MessageType.valueOf(env.type());
    } catch (IllegalArgumentException e) {
      replyError(session, env, "UNKNOWN_TYPE", "unknown message type: " + env.type());
      return;
    }
    try {
      switch (type) {
        case HELLO -> hello(session, env);
        case AUTH_OPERATOR -> authOperator(session, env);
        case AUTH_DEVICE -> authDevice(session, env);
        case HEARTBEAT -> heartbeat(session, env);
        case HEARTBEAT_ACK -> session.touchHeartbeat(); // client answering our heartbeat
        case LOGOUT -> {
          audit.append(actor(session), "LOGOUT", null, "OK", env.messageId());
          session.close();
        }
        default -> {
          if (!session.isAuthenticated()) {
            replyError(session, env, "NOT_AUTHENTICATED", "authenticate before " + type);
            return;
          }
          switch (type) {
            case ACK_RECEIVED -> ack(session, env, AckKind.RECEIVED);
            case ACK_USER -> ack(session, env, AckKind.USER);
            case ACK_CANCEL -> ack(session, env, AckKind.CANCEL);
            case SYNC_REQUEST -> syncRequest(session, env);
            case INCIDENT_CREATE -> incidentCreate(session, env);
            case INCIDENT_TRANSITION -> incidentTransition(session, env);
            case ALERT_PUBLISH -> alertPublish(session, env);
            case ALERT_UPDATE -> alertUpdate(session, env);
            case ALERT_CANCEL -> alertCancel(session, env);
            case STATUS_SUBSCRIBE -> statusSubscribe(session, env);
            default -> replyError(session, env, "UNSUPPORTED", "server does not accept " + type);
          }
        }
      }
    } catch (Json.BadPayload e) {
      replyError(session, env, "BAD_PAYLOAD", e.getMessage());
    } catch (SecurityException e) {
      // device/operator split denial: command-level FORBIDDEN, not INTERNAL (S-18)
      replyError(session, env, "FORBIDDEN", String.valueOf(e.getMessage()));
    } catch (SQLException e) {
      LOG.warn("command {} failed", type, e);
      // NFR-017 (S-04): storage/SQL details stay in the server log; the wire
      // gets a generic message correlated via requestMessageId.
      replyError(session, env, "INTERNAL", "internal server error");
    } catch (RuntimeException e) {
      LOG.warn("command {} failed", type, e);
      replyError(session, env, "INTERNAL", "internal server error");
    }
  }

  // ---- pre-auth -------------------------------------------------------------

  private void hello(ClientSession session, Envelope env) {
    ObjectNode payload = Json.obj();
    payload.put("version", Protocol.VERSION);
    payload.put("sessionId", session.sessionId());
    payload.put("heartbeatIntervalMs", config.heartbeatIntervalMs());
    payload.put("maxMissedHeartbeats", config.heartbeatMaxMissed());
    payload.put("serverTime", Json.ts(clock.instant()));
    payload.put("statusFeed", "ws://localhost:" + config.wsPort());
    reply(session, "SERVER_HELLO", env.messageId(), payload);
  }

  private void authOperator(ClientSession session, Envelope env) throws SQLException {
    if (session.isAuthenticated()) {
      // H3: a session may authenticate exactly once. Re-authenticating an already
      // bound session could swap its identity while the registry's byDeviceId /
      // byOperator index still routes the old identity's traffic here. Force a
      // LOGOUT + fresh connection to change identity instead.
      audit.append(actor(session), "AUTH_OPERATOR_REJECTED", "already-authenticated", "DENIED", env.messageId());
      reply(session, "AUTH_RESULT", env.messageId(),
          Json.obj().put("ok", false).put("code", "ALREADY_AUTHENTICATED"));
      return;
    }
    String username = Json.text(env.payload(), "username");
    String password = Json.text(env.payload(), "password");
    Optional<User> user = auth.authenticateOperator(username, password);
    if (user.isEmpty()) {
      audit.append(null, "AUTH_OPERATOR_FAILURE", username, "DENIED", env.messageId());
      ObjectNode payload = Json.obj().put("ok", false).put("code", "AUTH_FAILED");
      reply(session, "AUTH_RESULT", env.messageId(), payload);
      return;
    }
    User u = user.get();
    session.bindOperator(u.id(), u.role(), rbac.grantsFor(u.id()));
    audit.append(u.id(), "AUTH_OPERATOR_SUCCESS", u.role().name(), "OK", env.messageId());
    ObjectNode payload = Json.obj();
    payload.put("ok", true);
    payload.put("userId", u.id());
    payload.put("role", u.role().name());
    payload.put("sessionId", session.sessionId());
    payload.put("heartbeatIntervalMs", config.heartbeatIntervalMs());
    reply(session, "AUTH_RESULT", env.messageId(), payload);
  }

  private void authDevice(ClientSession session, Envelope env) throws SQLException {
    if (session.isAuthenticated()) {
      // H3: authenticate exactly once per session. Re-binding a different device
      // on a live session leaves the registry's stale byDeviceId entry routing the
      // previous device's ALERT_PUSH here (cross-device leak, trivial with the
      // shared demo credential). Require LOGOUT + reconnect to change device.
      audit.append(actor(session), "AUTH_DEVICE_REJECTED", "already-authenticated", "DENIED", env.messageId());
      reply(session, "AUTH_RESULT", env.messageId(),
          Json.obj().put("ok", false).put("code", "ALREADY_AUTHENTICATED"));
      return;
    }
    String deviceCode = Json.text(env.payload(), "deviceCode");
    String credential = Json.text(env.payload(), "credential");
    Optional<Device> device = auth.authenticateDevice(deviceCode, credential);
    if (device.isEmpty()) {
      audit.append(null, "AUTH_DEVICE_FAILURE", deviceCode, "DENIED", env.messageId());
      ObjectNode payload = Json.obj().put("ok", false).put("code", "AUTH_FAILED");
      reply(session, "AUTH_RESULT", env.messageId(), payload);
      return;
    }
    Device d = device.get();
    ServerStore.DeviceInfo info = store.deviceInfo(d.id())
        .orElseThrow(() -> new IllegalStateException("device has no floor: " + d.id()));
    session.bindDevice(d.id(),
        new DeviceLocation(d.id(), info.buildingId(), info.floorId(), info.zoneCode()),
        info.floorNumber(), info.zoneCode());
    sessions.deviceOnline(session);
    engine.resumeDevice(d.id());
    status.connectivityChanged();
    audit.append(d.id(), "AUTH_DEVICE_SUCCESS", info.zoneCode(), "OK", env.messageId());
    ObjectNode payload = Json.obj();
    payload.put("ok", true);
    payload.put("deviceId", d.id());
    payload.put("deviceCode", d.deviceCode());
    payload.put("buildingId", info.buildingId());
    payload.put("floorId", info.floorId());
    payload.put("floorNumber", info.floorNumber());
    payload.put("zoneCode", info.zoneCode() == null ? "" : info.zoneCode());
    payload.put("sessionId", session.sessionId());
    payload.put("heartbeatIntervalMs", config.heartbeatIntervalMs());
    reply(session, "AUTH_RESULT", env.messageId(), payload);
  }

  private void heartbeat(ClientSession session, Envelope env) {
    session.touchHeartbeat();
    reply(session, "HEARTBEAT_ACK", env.messageId(),
        Json.obj().put("serverTime", Json.ts(clock.instant()))
            .put("intervalMs", config.heartbeatIntervalMs()));
  }

  // ---- device commands ---------------------------------------------------------

  private void requireDevice(ClientSession session, Envelope env) {
    if (session.kind() != ClientSession.Kind.DEVICE) {
      throw new SecurityException("device session required");
    }
  }

  private void ack(ClientSession session, Envelope env, AckKind kind) throws SQLException {
    requireDevice(session, env);
    JsonNode p = env.payload();
    String alertId = Json.text(p, "alertId");
    String deliveryId = Json.text(p, "deliveryId");
    int revision = Json.optInt(p, "revision", -1);
    String deviceId = Json.text(p, "deviceId");
    if (!deviceId.equals(session.deviceId())) {
      replyError(session, env, "FORBIDDEN", "ack for another device");
      return;
    }
    Optional<Delivery> deliveryOpt = deliveries.findById(deliveryId);
    if (deliveryOpt.isEmpty()
        || !deliveryOpt.get().alertId().equals(alertId)
        || deliveryOpt.get().revision() != revision
        || !deliveryOpt.get().deviceId().equals(deviceId)) {
      replyError(session, env, "NOT_FOUND", "unknown delivery for alert/revision/device");
      return;
    }
    String userId = null;
    if (kind == AckKind.USER) {
      userId = Json.optText(p, "userId", null);
      if (userId != null) {
        // S-06: accountability — attribute the human gesture only to a real,
        // enabled user; an arbitrary userId from the payload is rejected.
        Optional<User> user = users.findById(userId);
        if (user.isEmpty() || !user.get().enabled()) {
          replyError(session, env, "FORBIDDEN", "unknown or disabled user");
          return;
        }
      }
    }
    // S-01: recordAck returns the inserted flag (ON CONFLICT DO NOTHING means a
    // duplicate no longer throws UNIQUE) — duplicate = ack kind already recorded.
    boolean inserted = deliveries.recordAck(deviceId, alertId, revision, deliveryId, kind, userId);
    boolean duplicate = !inserted;
    engine.onAck(alertId, deliveryId, deviceId, kind, clock.instant());
    audit.append(deviceId, "ACK_" + kind.name(), alertId, duplicate ? "DUPLICATE" : "OK",
        env.correlationId() == null ? env.messageId() : env.correlationId(),
        Json.obj().put("deliveryId", deliveryId).put("revision", revision));
    DeliveryStateView state = new DeliveryStateView(deliveries.findById(deliveryId).orElseThrow());
    ObjectNode payload = Json.obj();
    payload.put("ok", true);
    payload.put("duplicate", duplicate);
    payload.put("alertId", alertId);
    payload.put("deliveryId", deliveryId);
    payload.put("state", state.name());
    reply(session, "COMMAND_RESULT", env.messageId(), payload);
  }

  /** Small helper to read the post-ack state without leaking the record type. */
  private record DeliveryStateView(Delivery delivery) {
    String name() { return delivery.state().name(); }
  }

  private void syncRequest(ClientSession session, Envelope env) throws SQLException {
    requireDevice(session, env);
    ObjectNode payload = sync.syncResult(session.deviceId());
    audit.append(session.deviceId(), "SYNC", null, "OK", env.messageId());
    reply(session, "SYNC_RESULT", env.messageId(), payload);
  }

  // ---- operator commands ----------------------------------------------------------

  private void requireOperator(ClientSession session) {
    if (session.kind() != ClientSession.Kind.OPERATOR) {
      throw new SecurityException("operator session required");
    }
  }

  private void incidentCreate(ClientSession session, Envelope env) {
    requireOperator(session);
    JsonNode p = env.payload();
    String buildingId = Json.optText(p, "buildingId", config.buildingId());
    String kind = Json.text(p, "kind");
    String summary = Json.text(p, "summary");
    boolean simulation = Json.optBool(p, "simulation", true);
    if (!rbac.canPublish(session.role(), session.grants(), buildingId, null)) {
      forbidden(session, env, buildingId, "INCIDENT_CREATE");
      return;
    }
    IncidentService.Outcome outcome = incidents.create(session.userId(), buildingId, kind, summary,
        simulation, env.messageId());
    replyOutcome(session, env, outcome.ok(), outcome.code(), outcome.message(),
        Json.obj().put("incidentId", outcome.incidentId() == null ? "" : outcome.incidentId())
            .put("state", outcome.state() == null ? "" : outcome.state()));
  }

  private void incidentTransition(ClientSession session, Envelope env) throws SQLException {
    requireOperator(session);
    JsonNode p = env.payload();
    String incidentId = Json.text(p, "incidentId");
    String state = Json.text(p, "state");
    Optional<ServerStore.IncidentRow> row = store.findIncident(incidentId);
    if (row.isEmpty()) {
      replyError(session, env, "NOT_FOUND", "unknown incident: " + incidentId);
      return;
    }
    if (!rbac.canPublish(session.role(), session.grants(), row.get().buildingId(), null)) {
      forbidden(session, env, row.get().buildingId(), "INCIDENT_TRANSITION");
      return;
    }
    IncidentService.Outcome outcome = incidents.transition(session.userId(), incidentId, state,
        env.messageId());
    replyOutcome(session, env, outcome.ok(), outcome.code(), outcome.message(),
        Json.obj().put("incidentId", incidentId).put("state", outcome.state() == null ? "" : outcome.state()));
  }

  private void alertPublish(ClientSession session, Envelope env) throws SQLException {
    requireOperator(session);
    JsonNode p = env.payload();
    String requestId = Json.text(p, "requestId");
    String buildingId = Json.optText(p, "buildingId", config.buildingId());
    Integer floorNumber = p.hasNonNull("floorNumber") ? Json.optInt(p, "floorNumber", 0) : null;
    String zoneCode = Json.optText(p, "zoneCode", null);
    String category = Json.text(p, "category");
    Priority priority = parsePriority(Json.text(p, "priority"));
    String title = Json.text(p, "title");
    String instruction = Json.text(p, "instruction");
    boolean simulation = Json.optBool(p, "simulation", true);
    String urgency = Json.optText(p, "urgency", "Immediate");
    String severity = Json.optText(p, "severity", "Severe");
    String certainty = Json.optText(p, "certainty", "Observed");
    String incidentId = Json.optText(p, "incidentId", null);
    java.time.Instant expiresAt = Json.optInstant(p, "expiresAt");

    AlertService.Scope scope = alertService.resolveScope(buildingId, floorNumber, zoneCode);
    if (!rbac.canPublish(session.role(), session.grants(), buildingId, scope.floorId())) {
      forbidden(session, env, buildingId, "ALERT_PUBLISH");
      return;
    }
    AlertService.CommandOutcome outcome = alertService.publish(requestId, session.userId(), incidentId,
        scope, category, priority, title, instruction, simulation, urgency, severity, certainty,
        expiresAt, env.messageId());
    ObjectNode extra = Json.obj();
    if (outcome.alertId() != null) {
      extra.put("alertId", outcome.alertId());
      extra.put("revision", outcome.revision());
      extra.put("deliveryCount", outcome.deliveryCount());
      extra.put("duplicate", outcome.duplicate());
    }
    replyOutcome(session, env, outcome.ok(), outcome.code(), outcome.message(), extra);
  }

  private void alertUpdate(ClientSession session, Envelope env) throws SQLException {
    requireOperator(session);
    JsonNode p = env.payload();
    String requestId = Json.text(p, "requestId");
    String alertId = Json.text(p, "alertId");
    Optional<Alert> alert = alertRepo.findById(alertId);
    if (alert.isEmpty()) {
      replyError(session, env, "NOT_FOUND", "unknown alert: " + alertId);
      return;
    }
    if (!rbacForAlert(session, env, alert.get(), "ALERT_UPDATE")) return;
    AlertService.CommandOutcome outcome = alertService.update(requestId, session.userId(), alertId,
        Json.optText(p, "title", null), Json.optText(p, "instruction", null), env.messageId());
    ObjectNode extra = Json.obj();
    if (outcome.alertId() != null) {
      extra.put("alertId", outcome.alertId());
      extra.put("revision", outcome.revision());
      extra.put("deliveryCount", outcome.deliveryCount());
      extra.put("duplicate", outcome.duplicate());
    }
    replyOutcome(session, env, outcome.ok(), outcome.code(), outcome.message(), extra);
  }

  private void alertCancel(ClientSession session, Envelope env) throws SQLException {
    requireOperator(session);
    JsonNode p = env.payload();
    String requestId = Json.text(p, "requestId");
    String alertId = Json.text(p, "alertId");
    Optional<Alert> alert = alertRepo.findById(alertId);
    if (alert.isEmpty()) {
      replyError(session, env, "NOT_FOUND", "unknown alert: " + alertId);
      return;
    }
    if (!rbacForAlert(session, env, alert.get(), "ALERT_CANCEL")) return;
    AlertService.CommandOutcome outcome = alertService.cancel(requestId, session.userId(), alertId,
        Json.optText(p, "reason", ""), env.messageId());
    ObjectNode extra = Json.obj();
    if (outcome.alertId() != null) {
      extra.put("alertId", outcome.alertId());
      extra.put("revision", outcome.revision());
      extra.put("duplicate", outcome.duplicate());
    }
    replyOutcome(session, env, outcome.ok(), outcome.code(), outcome.message(), extra);
  }

  /** RBAC for update/cancel: the alert's building + its published floor scope. */
  private boolean rbacForAlert(ClientSession session, Envelope env, Alert alert, String action)
      throws SQLException {
    String floorId = null;
    Optional<aegis.persistence.AlertRevision> revision =
        alertRepo.findRevision(alert.id(), alert.currentRevision());
    if (revision.isPresent()) {
      JsonNode meta;
      try {
        meta = Json.MAPPER.readTree(revision.get().metadataJson());
      } catch (Exception e) {
        meta = Json.obj();
      }
      JsonNode scope = meta.path("scope");
      if (scope.hasNonNull("floorNumber")) {
        floorId = store.floorId(alert.buildingId(), scope.get("floorNumber").asInt()).orElse(null);
      }
    }
    if (!rbac.canPublish(session.role(), session.grants(), alert.buildingId(), floorId)) {
      forbidden(session, env, alert.buildingId(), action);
      return false;
    }
    return true;
  }

  private void statusSubscribe(ClientSession session, Envelope env) throws SQLException {
    requireOperator(session);
    // S-05 (SECURITY.md §4.2): the read plane is role/scope-gated — EMPLOYEE has
    // no access; SECOPS reads a granted building; FLOOR_MANAGER only granted floors.
    Role role = session.role();
    String buildingId = config.buildingId();
    if (role == Role.EMPLOYEE || role == Role.DEVICE
        || (role != Role.SYSTEM_ADMIN && !session.grants().coversBuilding(buildingId))) {
      forbidden(session, env, buildingId, "STATUS_SUBSCRIBE");
      return;
    }
    ObjectNode snapshot = status.snapshot();
    if (role == Role.FLOOR_MANAGER) {
      scopeSnapshotToGrants(snapshot, session, buildingId);
    }
    reply(session, "STATUS_SNAPSHOT", env.messageId(), snapshot);
  }

  /** Filters floors + activeAlerts down to the operator's granted floors (S-05). */
  private void scopeSnapshotToGrants(ObjectNode snapshot, ClientSession session, String buildingId)
      throws SQLException {
    java.util.Set<Integer> allowedFloors = new java.util.HashSet<>();
    ArrayNode keptFloors = Json.MAPPER.createArrayNode();
    for (JsonNode f : snapshot.get("floors")) {
      int floorNumber = f.get("floorNumber").asInt();
      String floorId = store.floorId(buildingId, floorNumber).orElse(null);
      if (floorId != null
          && rbac.canPublish(session.role(), session.grants(), buildingId, floorId)) {
        keptFloors.add(f);
        allowedFloors.add(floorNumber);
      }
    }
    snapshot.set("floors", keptFloors);
    ArrayNode keptAlerts = Json.MAPPER.createArrayNode();
    for (JsonNode a : snapshot.get("activeAlerts")) {
      int floorNumber = a.path("floorNumber").asInt(0);
      // floorNumber 0 = building-wide alert: visible to every granted floor
      if (floorNumber == 0 || allowedFloors.contains(floorNumber)) keptAlerts.add(a);
    }
    snapshot.set("activeAlerts", keptAlerts);
  }

  // ---- replies ---------------------------------------------------------------------

  private static Priority parsePriority(String raw) {
    try {
      return Priority.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new Json.BadPayload("unknown priority: " + raw);
    }
  }

  private void forbidden(ClientSession session, Envelope env, String buildingId, String action) {
    audit.append(session.userId(), action, buildingId, "FORBIDDEN", env.messageId());
    // command-level denial: COMMAND_RESULT{ok:false} (ERROR is for protocol/auth-gate faults)
    ObjectNode payload = Json.obj();
    payload.put("ok", false);
    payload.put("code", "FORBIDDEN");
    payload.put("message", "role " + session.role() + " lacks scope for " + action);
    reply(session, "COMMAND_RESULT", env.messageId(), payload);
  }

  private void replyOutcome(ClientSession session, Envelope env, boolean ok, String code,
                            String message, ObjectNode extra) {
    ObjectNode payload = extra == null ? Json.obj() : extra;
    payload.put("ok", ok);
    if (!ok) {
      payload.put("code", code == null ? "INTERNAL" : code);
      payload.put("message", message == null ? "" : message);
    }
    reply(session, "COMMAND_RESULT", env.messageId(), payload);
  }

  private void replyError(ClientSession session, Envelope env, String code, String message) {
    ObjectNode payload = Json.obj();
    payload.put("code", code);
    payload.put("message", message == null ? "" : message);
    if (env != null && env.messageId() != null) payload.put("requestMessageId", env.messageId());
    reply(session, "ERROR", env == null ? null : env.messageId(), payload);
  }

  private void reply(ClientSession session, String type, String correlationId, JsonNode payload) {
    Envelope env = new Envelope(Protocol.VERSION, type, Json.uuid(), correlationId,
        Json.ts(clock.instant()), payload);
    session.send(Priority.CRITICAL, env);
  }

  private static String actor(ClientSession session) {
    return session.kind() == ClientSession.Kind.DEVICE ? session.deviceId() : session.userId();
  }
}
