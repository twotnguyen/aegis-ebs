package aegis.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Shared Jackson mapper + small payload/envelope helpers (EBSP/2.0). */
public final class Json {
  public static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

  private Json() {}

  public static ObjectNode obj() { return MAPPER.createObjectNode(); }

  public static String ts(Instant instant) { return TS.format(instant); }

  public static String uuid() { return UUID.randomUUID().toString(); }

  /** Required non-blank text field, or {@link BadPayload} with the field name. */
  public static String text(JsonNode payload, String field) {
    JsonNode n = payload.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw new BadPayload("missing or blank field: " + field);
    }
    return n.asText();
  }

  public static String optText(JsonNode payload, String field, String fallback) {
    JsonNode n = payload.get(field);
    if (n == null || n.isNull()) return fallback;
    if (!n.isTextual()) throw new BadPayload("field must be text: " + field);
    return n.asText();
  }

  public static int optInt(JsonNode payload, String field, int fallback) {
    JsonNode n = payload.get(field);
    if (n == null || n.isNull()) return fallback;
    if (!n.isInt()) throw new BadPayload("field must be integer: " + field);
    return n.asInt();
  }

  public static boolean optBool(JsonNode payload, String field, boolean fallback) {
    JsonNode n = payload.get(field);
    if (n == null || n.isNull()) return fallback;
    if (!n.isBoolean()) throw new BadPayload("field must be boolean: " + field);
    return n.asBoolean();
  }

  public static Instant optInstant(JsonNode payload, String field) {
    String raw = optText(payload, field, null);
    if (raw == null) return null;
    try {
      return Instant.parse(raw);
    } catch (RuntimeException e) {
      throw new BadPayload("field must be UTC ISO-8601: " + field);
    }
  }

  public static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize JSON", e);
    }
  }

  /** Malformed command payload (mapped to ERROR/BAD_PAYLOAD on the wire). */
  public static final class BadPayload extends RuntimeException {
    public BadPayload(String message) { super(message); }
  }
}
