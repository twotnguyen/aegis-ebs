package aegis.server;

import aegis.persistence.AuditRepo;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Durable audit trail (audit_events). Best-effort: an audit write failure is
 * logged but never breaks the command path; correlationId ties every event to
 * the originating wire messageId/requestId.
 */
public final class AuditService {
  private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

  private final AuditRepo repo;

  public AuditService(AuditRepo repo) { this.repo = repo; }

  public void append(String actorId, String action, String scope, String outcome,
                     String correlationId, JsonNode payload) {
    try {
      repo.append(actorId, action, scope, outcome, correlationId,
          payload == null ? null : Json.write(payload));
    } catch (SQLException e) {
      LOG.warn("audit append failed action={} correlation={}: {}", action, correlationId, e.toString());
    }
  }

  public void append(String actorId, String action, String scope, String outcome, String correlationId) {
    append(actorId, action, scope, outcome, correlationId, null);
  }
}
