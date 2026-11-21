package aegis.server;

import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Incident lifecycle (incidents + incident_events + incident_alerts) with a
 * validated state machine. Transitions are audited and pushed to operator
 * sessions as INCIDENT_EVENT. Incident + timeline event writes are atomic (S-09).
 */
public final class IncidentService {
  private static final Logger LOG = LoggerFactory.getLogger(IncidentService.class);

  /** Allowed incident state transitions (docs schema CHECK + workflow). */
  private static final java.util.Map<String, Set<String>> TRANSITIONS = java.util.Map.of(
      "DRAFT", Set.of("OPEN", "ABORTED"),
      "OPEN", Set.of("INVESTIGATING", "CONTAINED", "RESOLVED", "ABORTED"),
      "INVESTIGATING", Set.of("CONTAINED", "RESOLVED", "ABORTED"),
      "CONTAINED", Set.of("RESOLVED", "INVESTIGATING", "ABORTED"),
      "RESOLVED", Set.of("CLOSED", "INVESTIGATING"),
      "CLOSED", Set.of(),
      "ABORTED", Set.of());

  public record Outcome(boolean ok, String incidentId, String state, String code, String message) {}

  private final ServerStore store;
  private final AuditService audit;
  private final SessionRegistry sessions;
  private final Clock clock;

  public IncidentService(ServerStore store, AuditService audit, SessionRegistry sessions, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.audit = Objects.requireNonNull(audit);
    this.sessions = Objects.requireNonNull(sessions);
    this.clock = Objects.requireNonNull(clock);
  }

  public Outcome create(String userId, String buildingId, String kind, String summary,
                        boolean simulation, String correlationId) {
    try {
      // S-09: incident row + opening event commit together (no orphan incidents)
      ServerStore.IncidentRow row = store.createIncidentAtomic(buildingId, userId, kind, summary,
          simulation, "OPEN", "OPENED", correlationId,
          Json.write(Json.obj().put("kind", kind).put("summary", summary)));
      audit.append(userId, "INCIDENT_CREATE", buildingId, "OK", correlationId,
          Json.obj().put("incidentId", row.id()).put("kind", kind));
      broadcastEvent(row.id(), "OPENED", buildingId, kind, summary, correlationId);
      return new Outcome(true, row.id(), "OPEN", null, null);
    } catch (SQLException e) {
      LOG.warn("incident create failed building={}", buildingId, e);
      // NFR-017 (S-04): details stay in the log, never on the wire
      return new Outcome(false, null, null, "INTERNAL", "incident create failed");
    }
  }

  public Outcome transition(String userId, String incidentId, String newState, String correlationId) {
    try {
      Optional<ServerStore.IncidentRow> rowOpt = store.findIncident(incidentId);
      if (rowOpt.isEmpty()) return new Outcome(false, null, null, "NOT_FOUND", "unknown incident");
      ServerStore.IncidentRow row = rowOpt.get();
      Set<String> allowed = TRANSITIONS.getOrDefault(row.state(), Set.of());
      if (!allowed.contains(newState)) {
        return new Outcome(false, incidentId, row.state(), "CONFLICT",
            "illegal transition " + row.state() + " -> " + newState);
      }
      boolean terminal = newState.equals("CLOSED") || newState.equals("ABORTED");
      // S-09: state change + timeline event commit together
      store.transitionIncidentAtomic(incidentId, newState, terminal ? clock.instant() : null,
          "TRANSITION_" + newState, userId, correlationId,
          Json.write(Json.obj().put("from", row.state()).put("to", newState)));
      audit.append(userId, "INCIDENT_TRANSITION", row.buildingId(), "OK", correlationId,
          Json.obj().put("incidentId", incidentId).put("from", row.state()).put("to", newState));
      broadcastEvent(incidentId, "TRANSITION_" + newState, row.buildingId(), row.kind(),
          row.summary(), correlationId);
      return new Outcome(true, incidentId, newState, null, null);
    } catch (SQLException e) {
      LOG.warn("incident transition failed incidentId={}", incidentId, e);
      return new Outcome(false, null, null, "INTERNAL", "transition failed");
    }
  }

  private void broadcastEvent(String incidentId, String eventKind, String buildingId,
                              String kind, String summary, String correlationId) {
    ObjectNode payload = Json.obj();
    payload.put("incidentId", incidentId);
    payload.put("eventKind", eventKind);
    payload.put("buildingId", buildingId);
    payload.put("kind", kind);
    payload.put("summary", summary);
    Envelope event = new Envelope(Protocol.VERSION, "INCIDENT_EVENT", Json.uuid(), correlationId,
        Json.ts(clock.instant()), payload);
    for (ClientSession s : sessions.all()) {
      if (s.kind() == ClientSession.Kind.OPERATOR && !s.isClosed()) {
        s.send(Priority.WARNING, event);
      }
    }
  }
}
