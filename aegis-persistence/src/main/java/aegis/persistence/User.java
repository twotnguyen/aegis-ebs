package aegis.persistence;

import aegis.protocol.Role;

import java.time.Instant;

/** users row. id is a UUID text; createdAt is UTC. */
public record User(String id, String username, String passwordHash, Role role,
                   boolean enabled, Instant createdAt) {}
