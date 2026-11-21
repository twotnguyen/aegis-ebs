package aegis.protocol;
import com.fasterxml.jackson.databind.JsonNode;
/** EBSP/2.0 envelope. payload is typed per MessageType; validated by codec/handlers. */
public record Envelope(int version, String type, String messageId, String correlationId, String sentAt, JsonNode payload) {}
