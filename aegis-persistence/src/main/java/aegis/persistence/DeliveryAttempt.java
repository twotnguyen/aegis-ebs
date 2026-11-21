package aegis.persistence;

import java.time.Instant;

/** delivery_attempts row: one transport attempt, new messageId each time. outcome nullable. */
public record DeliveryAttempt(String id, String deliveryId, String messageId,
                              Instant attemptedAt, String outcome) {}
