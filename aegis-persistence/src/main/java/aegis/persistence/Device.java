package aegis.persistence;

import java.time.Instant;

/** devices row. id is a UUID text; enrolledAt is UTC. */
public record Device(String id, String floorId, String deviceCode, String credentialHash,
                     boolean enabled, Instant enrolledAt) {}
