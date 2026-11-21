package aegis.persistence;

import aegis.protocol.Priority;

import java.time.Instant;

/** alerts row. expiresAt may be null (no expiry). */
public record Alert(String id, String buildingId, String createdBy, String category,
                    Priority priority, AlertState state, int currentRevision,
                    Instant createdAt, Instant expiresAt) {}
