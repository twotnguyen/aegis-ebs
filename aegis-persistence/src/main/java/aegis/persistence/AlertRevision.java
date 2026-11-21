package aegis.persistence;

import java.time.Instant;

/** alert_revisions row (immutable content per revision). createdBy may be null. */
public record AlertRevision(String alertId, int revision, String title, String body,
                            String metadataJson, Instant createdAt, String createdBy) {}
