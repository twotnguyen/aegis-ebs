package aegis.persistence;

import java.time.Instant;

/** deliveries row. Stable deliveryId per (alert, revision, device); nextRetryAt nullable. */
public record Delivery(String id, String alertId, int revision, String deviceId,
                       DeliveryState state, Instant nextRetryAt, int attemptCount,
                       Instant createdAt) {}
