package aegis.persistence;

import java.time.Instant;

/** audit_events row. actorId/scope/correlationId/payloadJson nullable. */
public record AuditEvent(String id, Instant occurredAt, String actorId, String action,
                         String scope, String outcome, String correlationId, String payloadJson) {}
