package aegis.client;

import aegis.protocol.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One ALERT_PUSH delivery as stored in the durable local inbox.
 * Mirrors the EBSP/2.0 ALERT_PUSH payload fixture (protocol_examples.json).
 * SIMULATION/TRAINING system: {@code simulation} defaults to true when absent.
 */
public record AlertRecord(
    String alertId, long revision, String deliveryId, String incidentId,
    String buildingId, String category, String priority, String title, String instruction,
    boolean simulation, String urgency, String severity, String certainty, String expiresAt,
    String messageId, String correlationId, String sentAt, String receivedAt) {

  /** Parse an ALERT_PUSH envelope; receivedAt is the local durable-store timestamp. */
  public static AlertRecord fromPush(Envelope e, String receivedAt) {
    if (!"ALERT_PUSH".equals(e.type())) {
      throw new IllegalArgumentException("not an ALERT_PUSH envelope: " + e.type());
    }
    return fromPayload(e.payload(), e.messageId(), e.correlationId(), e.sentAt(), receivedAt);
  }

  /** Parse an ALERT_PUSH-shaped payload (also used for SYNC_RESULT.alerts entries). */
  public static AlertRecord fromPayload(JsonNode p, String messageId, String correlationId,
                                        String sentAt, String receivedAt) {
    if (p == null || !p.isObject()) throw new IllegalArgumentException("alert payload must be a JSON object");
    String alertId = requireText(p, "alertId");
    JsonNode rev = p.get("revision");
    if (rev == null || !rev.canConvertToLong() || rev.longValue() < 1) {
      throw new IllegalArgumentException("alert revision must be a positive integer: " + rev);
    }
    String deliveryId = requireText(p, "deliveryId");
    String title = requireText(p, "title");
    return new AlertRecord(
        alertId, rev.longValue(), deliveryId,
        text(p, "incidentId"), text(p, "buildingId"), text(p, "category"), text(p, "priority"),
        title, text(p, "instruction"),
        !p.has("simulation") || p.get("simulation").asBoolean(true),
        text(p, "urgency"), text(p, "severity"), text(p, "certainty"), text(p, "expiresAt"),
        messageId, correlationId, sentAt, receivedAt);
  }

  /** Rebuild the ALERT_PUSH payload (for persistence / re-display). */
  public ObjectNode toPayload() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("alertId", alertId);
    n.put("revision", revision);
    n.put("deliveryId", deliveryId);
    putIfNotNull(n, "incidentId", incidentId);
    putIfNotNull(n, "buildingId", buildingId);
    putIfNotNull(n, "category", category);
    putIfNotNull(n, "priority", priority);
    n.put("title", title);
    putIfNotNull(n, "instruction", instruction);
    n.put("simulation", simulation);
    putIfNotNull(n, "urgency", urgency);
    putIfNotNull(n, "severity", severity);
    putIfNotNull(n, "certainty", certainty);
    putIfNotNull(n, "expiresAt", expiresAt);
    return n;
  }

  private static void putIfNotNull(ObjectNode n, String field, String value) {
    if (value != null) n.put(field, value);
  }

  private static String requireText(JsonNode p, String field) {
    JsonNode n = p.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw new IllegalArgumentException("missing or blank alert field: " + field);
    }
    return n.asText();
  }

  private static String text(JsonNode p, String field) {
    JsonNode n = p.get(field);
    return (n == null || n.isNull() || !n.isTextual()) ? null : n.asText();
  }
}
