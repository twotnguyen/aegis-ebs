package aegis.persistence;

import java.time.Instant;

/** device_acks row. userId nullable (device-level RECEIVED acks have no user). */
public record DeviceAck(String id, String deviceId, String alertId, int revision,
                        String deliveryId, AckKind ackKind, Instant ackedAt, String userId) {}
