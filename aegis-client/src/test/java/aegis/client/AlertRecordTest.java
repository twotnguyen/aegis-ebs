package aegis.client;

import aegis.protocol.Envelope;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** ALERT_PUSH payload parsing/validation against the protocol fixture shape. */
class AlertRecordTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String NOW = Instant.now().toString();

  private ObjectNode fixturePayload() {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("incidentId", "INC-TEST-001");
    p.put("alertId", "ALT-TEST-001");
    p.put("revision", 1);
    p.put("deliveryId", "DLV-TEST-001");
    p.put("buildingId", "B01");
    p.put("category", "FIRE");
    p.put("priority", "CRITICAL");
    p.put("title", "SIMULATION — Fire Drill");
    p.put("instruction", "Training only. Do not take real-world action.");
    p.put("simulation", true);
    p.put("urgency", "Immediate");
    p.put("severity", "Severe");
    p.put("certainty", "Observed");
    p.put("expiresAt", "2026-11-21T01:00:00Z");
    return p;
  }

  @Test void parsesFixturePayload() {
    AlertRecord a = AlertRecord.fromPayload(fixturePayload(), "msg-alert-001", "req-001", NOW, NOW);
    assertEquals("ALT-TEST-001", a.alertId());
    assertEquals(1, a.revision());
    assertEquals("DLV-TEST-001", a.deliveryId());
    assertEquals("CRITICAL", a.priority());
    assertTrue(a.simulation());
    assertEquals("msg-alert-001", a.messageId());
    assertEquals("req-001", a.correlationId());
  }

  @Test void fromPushRequiresAlertPushType() {
    Envelope e = new Envelope(Protocol.VERSION, "HEARTBEAT", "m1", null, NOW, fixturePayload());
    assertThrows(IllegalArgumentException.class, () -> AlertRecord.fromPush(e, NOW));
  }

  @Test void missingRequiredFieldsRejected() {
    for (String field : new String[]{"alertId", "revision", "deliveryId", "title"}) {
      ObjectNode p = fixturePayload();
      p.remove(field);
      assertThrows(IllegalArgumentException.class, () -> AlertRecord.fromPayload(p, "m", null, NOW, NOW),
          "missing " + field + " must be rejected");
    }
    ObjectNode blank = fixturePayload();
    blank.put("alertId", "  ");
    assertThrows(IllegalArgumentException.class, () -> AlertRecord.fromPayload(blank, "m", null, NOW, NOW));
  }

  @Test void revisionMustBePositiveInteger() {
    ObjectNode zero = fixturePayload();
    zero.put("revision", 0);
    assertThrows(IllegalArgumentException.class, () -> AlertRecord.fromPayload(zero, "m", null, NOW, NOW));
    ObjectNode text = fixturePayload();
    text.put("revision", "one");
    assertThrows(IllegalArgumentException.class, () -> AlertRecord.fromPayload(text, "m", null, NOW, NOW));
  }

  @Test void simulationDefaultsTrueForTrainingSystem() {
    ObjectNode p = fixturePayload();
    p.remove("simulation");
    assertTrue(AlertRecord.fromPayload(p, "m", null, NOW, NOW).simulation());
    p.put("simulation", false);
    assertFalse(AlertRecord.fromPayload(p, "m", null, NOW, NOW).simulation());
  }

  @Test void payloadRoundTrip() {
    AlertRecord a = AlertRecord.fromPayload(fixturePayload(), "m1", "c1", NOW, NOW);
    AlertRecord b = AlertRecord.fromPayload(a.toPayload(), "m1", "c1", NOW, NOW);
    assertEquals(a.alertId(), b.alertId());
    assertEquals(a.revision(), b.revision());
    assertEquals(a.title(), b.title());
    assertEquals(a.simulation(), b.simulation());
    assertEquals(a.expiresAt(), b.expiresAt());
  }
}
